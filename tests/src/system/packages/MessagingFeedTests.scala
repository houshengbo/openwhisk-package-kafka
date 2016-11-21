/*
 * Copyright 2015-2016 IBM Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package system.basic

import java.io._
import java.util.Properties

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.runner.RunWith
import org.scalatest.BeforeAndAfterAll
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner

import whisk.connector.kafka.KafkaProducerConnector
import whisk.core.WhiskConfig
import whisk.core.connector.Message
import whisk.utils.ExecutionContextFactory
import common.JsHelpers
import common.TestHelpers
import common.TestUtils
import common.Wsk
import common.WskActorSystem
import common.WskProps
import common.WskTestHelpers
import spray.json.DefaultJsonProtocol.StringJsonFormat
import spray.json.JsArray
import spray.json.JsString
import spray.json.pimpAny

@RunWith(classOf[JUnitRunner])
class MessagingFeedTests 
    extends FlatSpec
    with Matchers
    with WskActorSystem
    with BeforeAndAfterAll
    with TestHelpers
    with WskTestHelpers
    with JsHelpers {
    
    val groupid = "kafkatest"
    val topic = "test"
    val kafka_topic = "Dinosaurs"
    val sessionTimeout = 10 seconds

    implicit val wskprops = WskProps()
    val wsk = new Wsk()

    val messagingPackage = "/whisk.system/messaging"
    val kafkaFeed = "kafkaFeed"
    val messageHubFeed = "messageHubFeed"

    behavior of "Kafka connector"

    it should "fire a trigger when a message is posted to Kafka" in withAssetCleaner(wskprops) {
        val openwhisk_home = System.getenv("OPENWHISK_HOME")
        val file = new File(openwhisk_home + "/whisk.properties")
        val config = new WhiskConfig(Map("kafka.host" -> null, "kafka.host.port" -> null), Set(), file)
        assert(config.isValid)
        implicit val ec = ExecutionContextFactory.makeCachedThreadPoolExecutionContext()
        val producer = new KafkaProducerConnector(config.kafkaHost, ec)
        (wp, assetHelper) =>
            val triggerName = s"dummyKafkaTrigger-${System.currentTimeMillis}"

            // the package messaging should be there
            val packageGetResult = wsk.pkg.get(messagingPackage)
            packageGetResult.stdout should include("ok")
            println(config.kafkaHost)
            var kafka_brokers = JsArray(JsString(config.kafkaHost))
            val feedCreationResult = assetHelper.withCleaner(wsk.trigger, triggerName) {
                (trigger, name) =>
                    trigger.create(name, feed = Some(s"$messagingPackage/$kafkaFeed"), parameters = Map(
                        "brokers" -> kafka_brokers,
                        "topic" -> kafka_topic.toJson))
            }
            feedCreationResult.stdout should include("ok")
            Thread.sleep(2000)
            val message = new Message { override val serialize = s"${System.currentTimeMillis}" }
            val sent = Await.result(producer.send(kafka_topic, message), 10 seconds)
            val activations = wsk.activation.pollFor(N = 1, Some(triggerName), retries = 30).length
            println(s"Found activation size (should be exactly 1): $activations")
            withClue("Change feed trigger count: ") { activations should be(1) }
            producer.close()
    }
    
    it should "fire a trigger when a message is posted to the message hub" in withAssetCleaner(wskprops) {
        var credentials = TestUtils.getCredentials("message_hub")
        val user = credentials.get("user").getAsString()
        val password = credentials.get("password").getAsString()
        val kafka_admin_url = credentials.get("kafka_admin_url").getAsString()
        val api_key = credentials.get("api_key").getAsString()
        val kafka_brokers_sasl_json_array = credentials.get("kafka_brokers_sasl").getAsJsonArray()

        var vec = Vector[JsString]()
        var servers = s""
        val iter = kafka_brokers_sasl_json_array.iterator();
        while(iter.hasNext()){
            val server = iter.next().getAsString()
            vec = vec :+ JsString(server)
            servers = s"$servers$server,"
        }
        var kafka_brokers_sasl = JsArray(vec)
        
        val data = List("KafkaClient {",
            "com.ibm.messagehub.login.MessageHubLoginModule required",
            "serviceName=\"kafka\"", "username=\"" + user + "\"",
            "password=\"" + password + "\";",
            "};")
        val currentTime = s"${System.currentTimeMillis}"
        val tempConfigFile = s"jaas_$currentTime.conf"
        val writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(tempConfigFile)))
        for (x <- data) {
            writer.write(x + "\n")
        }
        writer.close()

        System.setProperty("java.security.auth.login.config", tempConfigFile)
        var props = new Properties()
        props.put("bootstrap.servers", servers);
        props.put("security.protocol", "SASL_SSL");
        props.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        props.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
 
        (wp, assetHelper) =>
            val triggerName = s"dummyMessageHubTrigger-$currentTime"

            val packageGetResult = wsk.pkg.get(messagingPackage)
            packageGetResult.stdout should include("ok")
            
            val feedCreationResult = assetHelper.withCleaner(wsk.trigger, triggerName) {
                (trigger, name) =>
                    trigger.create(name, feed = Some(s"$messagingPackage/$messageHubFeed"), parameters = Map(
                        "user" -> user.toJson,
                        "password" -> password.toJson,
                        "api_key" -> api_key.toJson,
                        "kafka_admin_url" -> kafka_admin_url.toJson,
                        "kafka_brokers_sasl" -> kafka_brokers_sasl,
                        "topic" -> topic.toJson))
            }
            feedCreationResult.stdout should include("ok")

            // It takes a moment for the consumer to fully initialize.
            Thread.sleep(2000)
            val producer = new KafkaProducer[String, String](props)
            val record = new ProducerRecord(topic, "key", currentTime)
            producer.send(record)
            producer.close()
            val activations = wsk.activation.pollFor(N = 1, Some(triggerName), retries = 30).length
            println(s"Found activation size (should be exactly 1): $activations")
            withClue("Change feed trigger count: ") { activations should be(1) }
            
            // Reset the property and delete and temp conf file.
            System.setProperty("java.security.auth.login.config", "")
            new File(tempConfigFile).delete()
    }

}