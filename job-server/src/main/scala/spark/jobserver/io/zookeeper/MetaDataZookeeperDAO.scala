package spark.jobserver.io.zookeeper

import com.typesafe.config.Config
import com.typesafe.config.ConfigRenderOptions
import com.typesafe.config.ConfigFactory
import org.joda.time.DateTime
import org.slf4j.LoggerFactory
import spark.jobserver.JobServer.InvalidConfiguration
import spark.jobserver.io._
import spark.jobserver.util.JsonProtocols
import spark.jobserver.util.Utils

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import org.apache.curator.framework.CuratorFramework

object MetaDataZookeeperDAO {
  private val baseDirPath = "spark.jobserver.zookeeperdao.dir"
  private val connectionStringPath = "spark.jobserver.zookeeperdao.connection-string"
  private val binariesDir = "/binaries"
  private val contextsDir = "/contexts"
  private val jobsDir = "/jobs"
}

class MetaDataZookeeperDAO(config: Config) extends MetaDataDAO {
  import MetaDataZookeeperDAO._
  import JsonProtocols._

  private val logger = LoggerFactory.getLogger(getClass)

  /*
   * Configuration
   */

  if (!config.hasPath(baseDirPath)) {
    throw new InvalidConfiguration(
      s"To use ZooKeeperDAO please specify ZK root dir for DAO in configuration file: $baseDirPath"
    )
  }

  if (!config.hasPath(connectionStringPath)) {
    throw new InvalidConfiguration(
      s"To use ZooKeeperDAO please specify ZooKeeper connection string: $connectionStringPath"
    )
  }

  private val connectionString = config.getString(connectionStringPath)
  private val zookeeperUtils = new ZookeeperUtils(connectionString, config.getString(baseDirPath))

  /*
   * Contexts
   */

  override def saveContext(contextInfo: ContextInfo): Future[Boolean] = {
    Future {
      Utils.usingResource(zookeeperUtils.getClient) {
        client =>
          val id = contextInfo.id
          logger.debug(s"Saving or updating context under: $contextsDir/$id")
          zookeeperUtils.write(client, contextInfo, s"$contextsDir/$id")
      }
    }
  }

  override def getContext(id: String): Future[Option[ContextInfo]] = {
    Future {
      Utils.usingResource(zookeeperUtils.getClient) {
        client =>
          logger.debug(s"Reading context from $contextsDir/$id")
          zookeeperUtils.read[ContextInfo](client, s"$contextsDir/$id") match {
            case Some(contextInfo) =>
              Some(contextInfo)
            case None =>
              logger.info(s"Didn't find any context information for the path: $contextsDir/$id")
              None
          }
      }
    }
  }

  override def getContextByName(name: String): Future[Option[ContextInfo]] = {
    Future {
      Utils.usingResource(zookeeperUtils.getClient) {
        client =>
          zookeeperUtils.list(client, contextsDir)
            .flatMap(id => zookeeperUtils.read[ContextInfo](client, s"$contextsDir/$id"))
            .filter(_.name == name)
            .sortBy(_.startTime.getMillis)
            .lastOption
      }
    }
  }

  override def getContexts(limit: Option[Int], statuses: Option[Seq[String]]): Future[Seq[ContextInfo]] = {
    Future {
      Utils.usingResource(zookeeperUtils.getClient) {
        client =>
          lazy val allContexts = zookeeperUtils.list(client, contextsDir)
            .flatMap(id => zookeeperUtils.read[ContextInfo](client, s"$contextsDir/$id"))
          lazy val filteredContexts = statuses match {
            case None => allContexts
            case Some(allowed) => allContexts.filter(c => allowed.contains(c.state))
          }
          lazy val sortedContexts = filteredContexts
            .sortBy(_.startTime.getMillis)(Ordering[Long].reverse)
          limit match {
            case None => sortedContexts
            case Some(l) => sortedContexts.take(l)
          }
      }
    }
  }

  /*
   * Jobs
   */

  override def saveJob(jobInfo: JobInfo): Future[Boolean] = {
    Future {
      Utils.usingResource(zookeeperUtils.getClient) {
        client =>
          val jobId = jobInfo.jobId
          logger.debug(s"Saving or updating job under: $jobsDir/$jobId")
          zookeeperUtils.write(client, jobInfo, s"$jobsDir/$jobId")
      }
    }
  }

  override def getJob(id: String): Future[Option[JobInfo]] = {
    Future {
      Utils.usingResource(zookeeperUtils.getClient) {
        client =>
          readJobInfo(client, id).flatMap(j => refreshBinary(client, j))
      }
    }
  }

  override def getJobs(limit: Int, status: Option[String]): Future[Seq[JobInfo]] = {
    Future {
      Utils.usingResource(zookeeperUtils.getClient) {
        client =>
          lazy val allJobs = zookeeperUtils.list(client, jobsDir)
            .flatMap(id => readJobInfo(client, id))
          lazy val filteredJobs =
            status match {
              case None => allJobs
              case Some(allowed) => allJobs.filter(_.state == allowed)
            }
          filteredJobs.sortBy(_.startTime.getMillis)(Ordering[Long].reverse)
            .take(limit)
            .flatMap(j => refreshBinary(client, j))
      }
    }
  }

  override def getJobsByContextId(contextId: String, statuses: Option[Seq[String]]): Future[Seq[JobInfo]] = {
    Future {
      Utils.usingResource(zookeeperUtils.getClient) {
        client =>
          lazy val allJobs = zookeeperUtils.list(client, jobsDir)
            .flatMap(id => readJobInfo(client, id))
            .filter(_.contextId == contextId)
          lazy val filteredJobs =
            statuses match {
              case None => allJobs
              case Some(allowed) => allJobs.filter(j => allowed.contains(j.state))
            }
          filteredJobs.sortBy(_.startTime.getMillis)(Ordering[Long].reverse)
            .flatMap(j => refreshBinary(client, j))
      }
    }
  }

  /*
   * Helpers
   */

  private def readJobInfo(client: CuratorFramework, jobId: String): Option[JobInfo] = {
    zookeeperUtils.read[JobInfo](client, s"$jobsDir/$jobId") match {
      case None =>
        logger.info(s"Didn't find any job information for the path: $jobsDir/$jobId")
        None

      case Some(j) => {
        Some(j)
      }
    }

  }

  /**
   * Within JobInfo only an initial copy of the binary info is stored
   * to identify the original BinaryInfo stored elsewhere
   *
   * The jobInfo shall only be returned if the BinaryInfo elsewhere still exists
   */
  private def refreshBinary(client: CuratorFramework, j: JobInfo): Option[JobInfo] = {
    zookeeperUtils.read[Seq[BinaryInfo]](client, s"$binariesDir/" + j.binaryInfo.appName) match {
      case Some(infoForBinary) =>
        // identified by name AND uploadTime
        infoForBinary.find(_.uploadTime.getMillis == j.binaryInfo.uploadTime.getMillis) match {
          case Some(_) => Some(j)
          case None =>
            logger.info(s"Didn't find a binary for name and uploadtime: ("
              + j.binaryInfo.appName + "," + j.binaryInfo.uploadTime + ")")
            None
        }
      case None =>
        logger.info(s"Didn't find a binary for name " + j.binaryInfo.appName)
        None
    }
  }

  /*
   * JobConfigs
   */

  override def saveJobConfig(jobId: String, config: Config): Future[Boolean] = {
    Future {
      Utils.usingResource(zookeeperUtils.getClient) {
        client =>
          val configRender = config.root().render(ConfigRenderOptions.concise())
          logger.debug(s"Saving or updating job config under: $jobsDir/$jobId/config")
          zookeeperUtils.write(client, configRender, s"$jobsDir/$jobId/config")
      }
    }
  }

  override def getJobConfig(jobId: String): Future[Option[Config]] = {
    Future {
      Utils.usingResource(zookeeperUtils.getClient) {
        client =>
          zookeeperUtils.read[String](client, s"$jobsDir/$jobId/config")
            .flatMap(render => Some(ConfigFactory.parseString(render)))
      }
    }
  }

  /*
   * Binaries
   */

  override def getBinary(name: String): Future[Option[BinaryInfo]] = {
    Future {
      Utils.usingResource(zookeeperUtils.getClient) {
        client =>
          zookeeperUtils.read[Seq[BinaryInfo]](client, s"$binariesDir/$name") match {
            case Some(infoForBinary) =>
              Some(infoForBinary.sortWith(_.uploadTime.getMillis > _.uploadTime.getMillis).head)
            case None =>
              logger.info(s"Didn't find any information for the path: $binariesDir/$name")
              None
          }
      }
    }
  }

  override def getBinaries: Future[Seq[BinaryInfo]] = {
    Future {
      Utils.usingResource(zookeeperUtils.getClient) {
        client =>
          zookeeperUtils.list(client, binariesDir).map(
            name => zookeeperUtils.read[Seq[BinaryInfo]](client, s"$binariesDir/$name"))
            .filter(_.isDefined)
            .map(binary => binary.get.head)
      }
    }
  }

  override def getBinariesByStorageId(storageId: String): Future[Seq[BinaryInfo]] = {
    Future {
      Utils.usingResource(zookeeperUtils.getClient) {
        client =>
          zookeeperUtils.list(client, binariesDir).flatMap(
            name => zookeeperUtils.read[Seq[BinaryInfo]](client, s"$binariesDir/$name")
            .getOrElse(Seq.empty[BinaryInfo]))
            .filter(_.binaryStorageId.getOrElse("") == storageId)
            .groupBy(_.appName).map(_._2.head).toSeq
      }
    }
  }

  override def saveBinary(name: String, binaryType: BinaryType,
                          uploadTime: DateTime, binaryStorageId: String): Future[Boolean] = {
    Future {
      Utils.usingResource(zookeeperUtils.getClient) {
        client =>
          val oldInfoForBinary = zookeeperUtils.read[Seq[BinaryInfo]](client, s"$binariesDir/$name")
          val newBinary = BinaryInfo(name, binaryType, uploadTime, Some(binaryStorageId))
          zookeeperUtils.write[Seq[BinaryInfo]](client,
            newBinary +: oldInfoForBinary.getOrElse(Seq.empty[BinaryInfo]),
            s"$binariesDir/$name")
      }
    }
  }

  override def deleteBinary(name: String): Future[Boolean] = {
    Future {
      Utils.usingResource(zookeeperUtils.getClient) {
        client =>
          zookeeperUtils.read[Seq[BinaryInfo]](client, s"$binariesDir/$name") match {
            case Some(_) => zookeeperUtils.delete(client, s"$binariesDir/$name")
            case None =>
              logger.info(s"Didn't find any information for the path: $binariesDir/$name")
              false
          }
      }
    }
  }
}