package awscala.ec2

import awscala._
import scala.collection.JavaConverters._
import com.amazonaws.services.{ ec2 => aws }
import com.amazonaws.services.ec2.model.TagDescription
import com.amazonaws.services.ec2.model.DescribeTagsRequest
import com.amazonaws.services.ec2.model.InstanceStatus
import com.amazonaws.services.ec2.model.DescribeInstanceStatusRequest

object EC2 {

  def apply(credentials: Credentials = CredentialsLoader.load()): EC2 = new EC2Client(credentials)
  def apply(accessKeyId: String, secretAccessKey: String): EC2 = apply(Credentials(accessKeyId, secretAccessKey))

  def at(region: Region): EC2 = apply().at(region)
}

/**
 * Amazon EC2 Java client wrapper
 * @see "http://docs.aws.amazon.com/AWSJavaSDK/latest/javadoc/"
 */
trait EC2 extends aws.AmazonEC2 {

  lazy val CHECK_INTERVAL = 5000L

  def at(region: Region): EC2 = {
    this.setRegion(region)
    this
  }

  // ------------------------------------------
  // Instances
  // ------------------------------------------

  def instances: Seq[Instance] = {
    describeInstances.getReservations.asScala.flatMap(_.getInstances.asScala.toSeq.map(Instance(_)))
  }

  def instances(instanceId: String*): Seq[Instance] = {
    describeInstances(new aws.model.DescribeInstancesRequest().withInstanceIds(instanceId: _*))
      .getReservations.asScala.flatMap(_.getInstances.asScala).map(Instance(_))
  }

  def runAndAwait(
    imageId: String,
    keyPair: KeyPair,
    instanceType: InstanceType = InstanceType.T1_Micro,
    min: Int = 1,
    max: Int = 1): Seq[Instance] = {

    runAndAwait(new RunInstancesRequest(imageId, min, max).withKeyName(keyPair.name).withInstanceType(instanceType))
  }

  def runAndAwait(request: aws.model.RunInstancesRequest): Seq[Instance] = {
    var requestedInstances: Seq[Instance] = runInstances(request).getReservation.getInstances.asScala.map(Instance(_))
    val ids = requestedInstances.map(_.instanceId)

    def checkStatus(checkIds: Seq[String]): Seq[Instance] = instances.filter(i => checkIds.contains(i.instanceId))

    val pendingState = new aws.model.InstanceState().withName(aws.model.InstanceStateName.Pending)
    while (requestedInstances.exists(_.state.getName == pendingState.getName)) {
      Thread.sleep(CHECK_INTERVAL)
      requestedInstances = checkStatus(ids)
    }
    requestedInstances
  }

  def start(instance: Instance*) = startInstances(new aws.model.StartInstancesRequest()
    .withInstanceIds(instance.map(_.instanceId): _*))

  def stop(instance: Instance*) = stopInstances(new aws.model.StopInstancesRequest()
    .withInstanceIds(instance.map(_.instanceId): _*))

  def terminate(instance: Instance*) = terminateInstances(new aws.model.TerminateInstancesRequest()
    .withInstanceIds(instance.map(_.instanceId): _*))

  def reboot(instance: Instance*) = rebootInstances(new aws.model.RebootInstancesRequest()
    .withInstanceIds(instance.map(_.instanceId): _*))

  // ------------------------------------------
  // Key Pairs
  // ------------------------------------------

  def keyPairs: Seq[KeyPair] = describeKeyPairs.getKeyPairs.asScala.map(KeyPair(_))

  def keyPair(name: String): Option[KeyPair] = {
    describeKeyPairs(new aws.model.DescribeKeyPairsRequest().withKeyNames(name))
      .getKeyPairs.asScala.map(KeyPair(_)).headOption
  }

  def createKeyPair(name: String): KeyPair = KeyPair(createKeyPair(new aws.model.CreateKeyPairRequest(name)).getKeyPair)

  def delete(keyPair: KeyPair): Unit = deleteKeyPair(keyPair.name)
  def deleteKeyPair(name: String): Unit = deleteKeyPair(new aws.model.DeleteKeyPairRequest(name))

  // ------------------------------------------
  // Security Groups
  // ------------------------------------------

  def securityGroups: Seq[SecurityGroup] = describeSecurityGroups.getSecurityGroups.asScala.map(SecurityGroup(_))

  def securityGroup(name: String): Option[SecurityGroup] = {
    describeSecurityGroups(new aws.model.DescribeSecurityGroupsRequest().withGroupNames(name))
      .getSecurityGroups.asScala.map(SecurityGroup(_)).headOption
  }

  def createSecurityGroup(name: String, description: String): Option[SecurityGroup] = {
    createSecurityGroup(new aws.model.CreateSecurityGroupRequest(name, description))
    securityGroup(name)
  }

  def delete(securityGroup: SecurityGroup): Unit = deleteSecurityGroup(securityGroup.groupName)
  def deleteSecurityGroup(name: String): Unit = {
    deleteSecurityGroup(new aws.model.DeleteSecurityGroupRequest().withGroupName(name))
  }
  
  def tags() {   
    case class State(items: List[TagDescription], nextToken: Option[String])

    @scala.annotation.tailrec
    def next(state: State): (Option[TagDescription], State) = state match {
      case State(head :: tail, nextToken) => (Some(head), State(tail, nextToken))
      case State(Nil, Some(nextToken)) => {
        val result = describeTags(new DescribeTagsRequest().withNextToken(nextToken))
        next(State(result.getTags().asScala.toList, Option(result.getNextToken())))
      }
      case State(Nil, None) => (None, state)
    }

    def toStream(state: State): Stream[TagDescription] =
      next(state) match {
        case (Some(item), nextState) => Stream.cons(item, toStream(nextState))
        case (None, _) => Stream.Empty
      }

    val result = describeTags()
    toStream(State(result.getTags().asScala.toList, Option(result.getNextToken())))
  }
  
  def instanceStatuses: Seq[InstanceStatus] = {
    case class ISState(items: List[InstanceStatus], nextToken: Option[String])

    @scala.annotation.tailrec
    def next(state: ISState): (Option[InstanceStatus], ISState) = state match {
      case ISState(head :: tail, nextToken) => (Some(head), ISState(tail, nextToken))
      case ISState(Nil, Some(nextToken)) => {
        val result = describeInstanceStatus(new DescribeInstanceStatusRequest().withNextToken(nextToken))
        next(ISState(result.getInstanceStatuses().asScala.toList, Option(result.getNextToken())))
      }
      case ISState(Nil, None) => (None, state)
    }

    def toStream(state: ISState): Stream[InstanceStatus] =
      next(state) match {
        case (Some(item), nextState) => Stream.cons(item, toStream(nextState))
        case (None, _) => Stream.Empty
      }

    val result = describeInstanceStatus()
    toStream(ISState(result.getInstanceStatuses().asScala.toList, Option(result.getNextToken())))
  }

}

/**
 * Default Implementation
 *
 * @param credentials credentials
 */
class EC2Client(credentials: Credentials = CredentialsLoader.load())
  extends aws.AmazonEC2Client(credentials)
  with EC2
