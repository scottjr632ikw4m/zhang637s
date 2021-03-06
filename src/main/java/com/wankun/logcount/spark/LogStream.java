package com.wankun.logcount.spark;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.client.HConnection;
import org.apache.hadoop.hbase.client.HConnectionManager;
import org.apache.hadoop.hbase.client.HTableInterface;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.http.HttpServer2;
import org.apache.hadoop.net.NetUtils;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.streaming.Duration;
import org.apache.spark.streaming.api.java.JavaDStream;
import org.apache.spark.streaming.api.java.JavaPairReceiverInputDStream;
import org.apache.spark.streaming.api.java.JavaStreamingContext;
import org.apache.spark.streaming.kafka.KafkaUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Maps;

import scala.Tuple2;

public class LogStream {
    private final static Logger logger = LoggerFactory.getLogger(LogStream.class);

    private static HConnection connection = null;
    private static HTableInterface table = null;
    private static HttpServer2 infoServer = null;

    public static void openHBase(String tablename) throws IOException {
        Configuration conf = HBaseConfiguration.create();
        synchronized (HConnection.class) {
            if (connection == null)
                connection = HConnectionManager.createConnection(conf);
        }

        synchronized (HTableInterface.class) {
            if (table == null) {
                table = connection.getTable(tablename);
            }
        }

		/* start http info server */
        HttpServer2.Builder builder = new HttpServer2.Builder().setName("recsys").setConf(conf);
        InetSocketAddress addr = NetUtils.createSocketAddr("0.0.0.0", 8089);
        builder.addEndpoint(URI.create("http://" + NetUtils.getHostPortString(addr)));
        infoServer = builder.build();
        infoServer.addServlet("monitor", "/monitor", RecsysLogs.class);
        infoServer.setAttribute("htable", table);
        infoServer.setAttribute("conf", conf);
        infoServer.start();
    }

    public static void closeHBase() {
        if (table != null)
            try {
                table.close();
            } catch (IOException e) {
                logger.error("?????? table ??????", e);
            }
        if (connection != null)
            try {
                connection.close();
            } catch (IOException e) {
                logger.error("?????? connection ??????", e);
            }
        if (infoServer != null && infoServer.isAlive())
            try {
                infoServer.stop();
            } catch (Exception e) {
                logger.error("?????? infoServer ??????", e);
            }
    }

    public static void main(String[] args) {
        try {
            openHBase("recsys_logs");
        } catch (IOException e) {
            logger.error("??????HBase ????????????", e);
            System.exit(-1);
        }

        logger.info("------open hbase----------");
        SparkConf conf = new SparkConf().setAppName("recsys log stream").setMaster("local[2]");
        JavaStreamingContext ssc = new JavaStreamingContext(conf, new Duration(1000));
        Map<String, Integer> topicMap = Maps.newHashMap();
        topicMap.put("recsys", 3);
        JavaPairReceiverInputDStream<String, String> logstream = KafkaUtils.createStream(ssc,
                "hdp1:2181", "recsys_group0", topicMap);
        logstream.print();

        JavaDStream<String> lines = logstream.map(new Function<Tuple2<String, String>, String>() {
            private static final long serialVersionUID = -1801798365843350169L;
            @Override
            public String call(Tuple2<String, String> tuple2) {
                logger.info("------=====>>>>>" + tuple2._2());
                return tuple2._2();
            }
        }).filter(new Function<String, Boolean>() {
            private static final long serialVersionUID = 7786877762996470593L;
            @Override
            public Boolean call(String msg) throws Exception {
                return msg.indexOf("INFO") > 0;
            }
        });

        // ??????Log???????????????????????????HBase???
        JavaDStream<Long> nums = lines.count();
        nums.foreachRDD(new Function<JavaRDD<Long>, Void>() {
            private SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd HH:mm:ss");
            @Override
            public Void call(JavaRDD<Long> rdd) throws Exception {
                Long num = rdd.take(1).get(0);
                String ts = sdf.format(new Date());
                Put put = new Put(Bytes.toBytes(ts));
                put.add(Bytes.toBytes("f1"), Bytes.toBytes("nums"), Bytes.toBytes(num));
                table.put(put);
                return null;
            }
        });
        ssc.start();
        ssc.awaitTermination();
    }
}
