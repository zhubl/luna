package luna.input;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.log4j.BasicConfigurator;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.simple.JSONValue;

import luna.config.ConfigHelp;
import luna.filter.ElasticsearchFilter;
import luna.filter.BulkElasticsearchFilter;

/**
 * 
* Copyright: Copyright (c) 2017 XueErSi
* 
* @ClassName: KafkaInput.java
* @Description: Kafka Client
*
* @version: v1.0.0
* @author: GaoXing Chen
* @date: 2017年8月21日 下午6:34:15 
*
* Modification History:
* Date         Author          Version            Description
*---------------------------------------------------------*
* 2017年8月21日     GaoXing Chen      v1.0.0               添加注释
 */
public class KafkaInput extends BaseInput{
	private Properties props;				//kafka properties
	private List<String> topics;			//topic list
	private String groupId;					//consumer group ID
	private ExecutorService executor;
	private int numConsumers;				//consumer thread number
	private String maxFetchByte;			//A poll max fetch byte
	private int maxPollRecords;				//A poll max poll record number
	private int bulkEdge;					//The record number edge to use Elasticsearch bulk
	private Logger log;						
	private List<ConsumerLoop> consumers;
	private final ElasticsearchFilter esfilter;
	private final BulkElasticsearchFilter bulkEsFilter;
	private final  Map inputConfigs;		//config map from example.yml

	public KafkaInput(String configFile) {
		Map configs=null;
		try {
			configs=ConfigHelp.parse(configFile);
		} catch (Exception e) {
			e.printStackTrace();
			log.error(e);
		}
		inputConfigs = (Map) configs.get("NewKafka");
		final  Map outputConfigs = (Map) configs.get("Elasticsearch");
		esfilter=new ElasticsearchFilter(outputConfigs);
		bulkEsFilter = new BulkElasticsearchFilter(outputConfigs);
		prepare();
	}

	/*init log and properties*/
	private void prepare() {
		BasicConfigurator.configure();
		log=LogManager.getLogger((String)inputConfigs.get("logger"));
		numConsumers = (Integer)inputConfigs.get("threadnum");
		groupId = (String)inputConfigs.get("group.id");
		topics=(List<String>) inputConfigs.get("topics");
		maxFetchByte = ""+inputConfigs.get("max.fetch.byte");
		maxPollRecords=(int)inputConfigs.get("max.poll.records");
		bulkEdge = (int) inputConfigs.get("bulk.edge");
		props = new Properties();
		props.put("bootstrap.servers", inputConfigs.get("bootstrap.servers"));
		props.put("group.id", groupId);
		props.put("key.deserializer", StringDeserializer.class.getName());
		props.put("value.deserializer", StringDeserializer.class.getName());
		props.put("max.partition.fetch.bytes",maxFetchByte);
		props.put("max.poll.records",maxPollRecords);
		consumers = new ArrayList<ConsumerLoop>();
	}

	public void excute() {
		executor = Executors.newFixedThreadPool(numConsumers);
		int topicNum = topics.size();
		log.info("threadnum: "+numConsumers+" and topicnum: "+ topicNum);

		//assign consumer thread for topic
		for (int i = 0; i < numConsumers; i++) {
			for(int j=0;j<topicNum;j++){
				if(j%numConsumers==i){
					ConsumerLoop consumer = new ConsumerLoop(props, Arrays.asList(topics.get(j)));
					consumers.add(consumer);
					executor.submit(consumer);
				}
			}
		}
		
		/**
		 * safe exit 
		 */
		Runtime.getRuntime().addShutdownHook(new Thread() {
			public void run() {
				consumers.forEach(consumerThread -> consumerThread.shutdown());
				executor.shutdown();
				log.info("All comsumer is shutdown!");
				try {
					executor.awaitTermination(5000, TimeUnit.MILLISECONDS);
				} catch (InterruptedException e) {
					log.error(e);
				}
			}
		});
	}

	public void shutdown() {
        consumers.forEach(consumerThread -> consumerThread.shutdown());
        executor.shutdown();
        log.info("All comsumer is shutdown!");
        try {
            executor.awaitTermination(5000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            log.error(e);
        }
    }

	public class ConsumerLoop implements Runnable {
		private final KafkaConsumer<String, String> consumer;
		private final List<String> topics;

		public ConsumerLoop(Properties props, List<String> topics) {
			this.topics = topics;
			this.consumer = new KafkaConsumer<String, String>(props);
		}

		public void run() {
			try {
				consumer.subscribe(topics,new ConsumerRebalanceListener() {
	                public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
	                }

	                public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
	                    partitions.forEach(partition -> {
	                        log.info("Rebalance happened " + partition.topic() + ":" + partition.partition());
	                    });
	                }
	            });
				log.info("Thread-"+Thread.currentThread().getId()+" Get kafka client!");
				ConsumerRecords<String, String> records;
				while (true) {
					records = consumer.poll(Long.MAX_VALUE);
					System.out.println(records);
					if(records.count()<bulkEdge){
						for (ConsumerRecord<String, String> record : records) {
							try {
								log.info("Thread-" + Thread.currentThread().getId() + ": " + record);
								esfilter.filter((Map<String, Object>) JSONValue.parseWithException(record.value()));
							} catch (Exception e) {
								e.printStackTrace();
								log.error("Thread " + Thread.currentThread().getId() + ": " + e);
							}
						}
					}else{
						bulkEsFilter.prepare();
						for (ConsumerRecord<String, String> record : records) {
							try {
								log.info("Thread-" + Thread.currentThread().getId() + ": " + record);
								bulkEsFilter.filter((Map<String, Object>) JSONValue.parseWithException(record.value()));
							} catch (Exception e) {
								e.printStackTrace();
								log.error("Thread " + Thread.currentThread().getId() + ": " + e);
							}
						}
						bulkEsFilter.emit();
					}
				}
			} catch (WakeupException e) {
				// ignore for shutdown
			} finally {
				consumer.close();
				log.info("Consumer Thread "+ Thread.currentThread().getId() + "is closed!");
			}
		}

		public void shutdown() {
			consumer.wakeup();
		}

	}
}
