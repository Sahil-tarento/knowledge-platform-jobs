package org.sunbird.job.curatedprogram.functions

import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.configuration.Configuration
import org.apache.flink.streaming.api.functions.ProcessFunction
import org.slf4j.LoggerFactory
import org.sunbird.job.postpublish.helpers.DialHelper
import org.sunbird.job.postpublish.models.ExtDataConfig
import org.sunbird.job.postpublish.task.PostPublishProcessorConfig
import org.sunbird.job.util.{CassandraUtil, HttpUtil, Neo4JUtil}
import org.sunbird.job.{BaseProcessFunction, Metrics}

import java.util
import scala.collection.JavaConverters._

class DIALCodeLinkFunction(config: PostPublishProcessorConfig, httpUtil: HttpUtil,
                           @transient var neo4JUtil: Neo4JUtil = null,
                           @transient var cassandraUtil: CassandraUtil = null)
                          (implicit val stringTypeInfo: TypeInformation[String])
  extends BaseProcessFunction[java.util.Map[String, AnyRef], String](config)
    with DialHelper {

  private[this] val logger = LoggerFactory.getLogger(classOf[DIALCodeLinkFunction])

  override def open(parameters: Configuration): Unit = {
    super.open(parameters)
    cassandraUtil = new CassandraUtil(config.dbHost, config.dbPort)
    neo4JUtil = new Neo4JUtil(config.graphRoutePath, config.graphName)
  }

  override def close(): Unit = {
    cassandraUtil.close()
    super.close()
  }

  override def processElement(edata: java.util.Map[String, AnyRef], context: ProcessFunction[java.util.Map[String, AnyRef], String]#Context, metrics: Metrics): Unit = {
    logger.info(s"Link DIAL Code operation triggered with object : ${edata}")
    metrics.incCounter(config.dialLinkCount)
    try {
      val dialcode: String = getDialcode(edata)
      metrics.incCounter(config.dialLinkSuccessCount)
      if (!dialcode.isEmpty)
        createQRGeneratorEvent(edata, dialcode, context, config)(metrics, ExtDataConfig(config.dialcodeKeyspaceName, config.dialcodeTableName), cassandraUtil)
    } catch {
      case ex: Throwable =>
        logger.error(s"Error while processing message for identifier : ${edata.get("identifier").asInstanceOf[String]}.", ex)
        metrics.incCounter(config.dialLinkFailedCount)
        throw ex
    }
  }

  def getDialcode(edata: java.util.Map[String, AnyRef]): String = {

    val identifier = edata.get("identifier").asInstanceOf[String]
    val dialcodes = fetchExistingDialcodes(edata)
    logger.info(s"Dialcodes fetched: ${dialcodes}") // temp
    if (dialcodes.isEmpty) {
      logger.info(s"No Dial Code found. Checking for Reserved Dialcodes.")
      var reservedDialCode: util.Map[String, Integer] = fetchExistingReservedDialcodes(edata)
      if (reservedDialCode.isEmpty) {
        logger.info(s"No Reserved Dial Code found. Sending request for Reserving Dialcode.")
        reservedDialCode = reserveDialCodes(edata, config)(httpUtil)
      }
      reservedDialCode.asScala.keys.headOption match {
        case Some(dialcode: String) => {
          updateDIALToObject(identifier, dialcode)(neo4JUtil)
          dialcode
        }
        case _ => {
          logger.info(s"Couldn't reserve any dialcodes for object with identifier:${identifier}")
          throw new Exception(s"Failed to Reserve dialcode for object with identifier:${identifier}.")
        }
      }
    } else if (validateQR(dialcodes.get(0))(ExtDataConfig(config.dialcodeKeyspaceName, config.dialcodeTableName), cassandraUtil)) "" else dialcodes.get(0)
  }

  override def metricsList(): List[String] = {
    List(config.dialLinkCount, config.qrImageGeneratorEventCount, config.dialLinkSuccessCount, config.dialLinkFailedCount)
  }
}
