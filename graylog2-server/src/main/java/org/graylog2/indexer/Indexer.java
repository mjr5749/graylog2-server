package org.graylog2.indexer;

import com.google.common.base.Joiner;
import com.google.common.collect.Maps;
import org.apache.commons.io.FileUtils;
import org.elasticsearch.ElasticSearchTimeoutException;
import org.elasticsearch.action.WriteConsistencyLevel;
import org.elasticsearch.action.admin.cluster.health.ClusterHealthRequest;
import org.elasticsearch.action.admin.cluster.node.info.NodesInfoRequest;
import org.elasticsearch.action.admin.cluster.node.info.NodesInfoResponse;
import org.elasticsearch.action.bulk.BulkItemResponse;
import org.elasticsearch.action.bulk.BulkRequestBuilder;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.index.IndexRequest.OpType;
import org.elasticsearch.action.index.IndexRequestBuilder;
import org.elasticsearch.action.support.replication.ReplicationType;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.settings.loader.YamlSettingsLoader;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.node.Node;
import org.elasticsearch.node.NodeBuilder;
import org.graylog2.Configuration;
import org.graylog2.Core;
import org.graylog2.UI;
import org.graylog2.indexer.cluster.Cluster;
import org.graylog2.indexer.counts.Counts;
import org.graylog2.indexer.indices.Indices;
import org.graylog2.indexer.messages.Messages;
import org.graylog2.indexer.searches.Searches;
import org.graylog2.plugin.Message;
import org.graylog2.plugin.indexer.MessageGateway;
import org.joda.time.Period;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.elasticsearch.node.NodeBuilder.nodeBuilder;

// TODO this class blocks for most of its operations, but is called from the main thread for some of them
// TODO figure out how to gracefully deal with failure to connect (or losing connection) to the elastic search cluster!
public class Indexer {
    private static final Logger LOG = LoggerFactory.getLogger(Indexer.class);

    private Client client;
    private MessageGateway messageGateway;
    public static final String TYPE = "message";
    
    private Searches searches;
    private Counts counts;
    private Messages messages;
    private Cluster cluster;
    private Indices indices;

    private LinkedBlockingQueue<BulkItemResponse[]> failureQueue;

    private Core server;

    public static enum DateHistogramInterval {
        YEAR(Period.years(1)),
        QUARTER(Period.months(3)), // TODO this isn't really correct, is it?
        MONTH(Period.months(1)),
        WEEK(Period.weeks(1)),
        DAY(Period.days(1)),
        HOUR(Period.hours(1)),
        MINUTE(Period.minutes(1));

        private final Period period;

        DateHistogramInterval(Period period) {
            this.period = period;
        }

        public Period getPeriod() {
            return period;
        }
    }

    public Indexer(Core graylogServer) {
        this.server = graylogServer;

        this.failureQueue = new LinkedBlockingQueue<BulkItemResponse[]>(1000);
    }

    public void start() {
        final NodeBuilder builder = nodeBuilder().client(true);
        Map<String, String> settings = readNodeSettings(server.getConfiguration());

        builder.settings().put(settings);
        final Node node = builder.node();
        client = node.client();

        try {
            client.admin().cluster().health(new ClusterHealthRequest().waitForYellowStatus()).actionGet(5, SECONDS);
        } catch(ElasticSearchTimeoutException e) {
            UI.exitHardWithWall("No ElasticSearch master was found.", new String[]{ "graylog2-server/configuring-and-tuning-elasticsearch-for-graylog2-v0200" });
        }

        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                node.close();
            }
        });

        messageGateway = new MessageGatewayImpl(server);
        searches = new Searches(client, server);
        counts = new Counts(client, server);
        messages = new Messages(client, server);
        cluster = new Cluster(client, server);
        indices = new Indices(client, server);
    }

    // default visibility for tests
    Map<String, String> readNodeSettings(Configuration conf) {
        Map<String, String> settings = Maps.newHashMap();

        // Standard Configuration.
        settings.put("cluster.name", conf.getEsClusterName());

        settings.put("node.name", conf.getEsNodeName());
        settings.put("node.master", Boolean.toString(conf.isEsIsMasterEligible()));
        settings.put("node.data", Boolean.toString(conf.isEsStoreData()));

        settings.put("http.enabled", Boolean.toString(conf.isEsIsHttpEnabled()));
        settings.put("transport.tcp.port", String.valueOf(conf.getEsTransportTcpPort()));

        settings.put("discovery.initial_state_timeout", conf.getEsInitialStateTimeout());
        settings.put("discovery.zen.ping.multicast.enabled", Boolean.toString(conf.isEsMulticastDiscovery()));

        if (conf.getEsUnicastHosts() != null) {
            settings.put("discovery.zen.ping.unicast.hosts", Joiner.on(",").join(conf.getEsUnicastHosts()));
        }

        // Overwrite from a custom ElasticSearch config file.
        try {
            final String esConfigFilePath = conf.getElasticSearchConfigFile();
            if (esConfigFilePath != null) {
                String esSettings = FileUtils.readFileToString(new File(esConfigFilePath));
                settings.putAll(new YamlSettingsLoader().load(esSettings));
            }
        } catch (IOException e) {
            LOG.warn("Cannot read elasticsearch configuration.");
        }

        return settings;
    }

    public Client getClient() {
        return client;
    }

    public MessageGateway getMessageGateway() {
        return messageGateway;
    }

    public String nodeIdToName(String nodeId) {
        if (nodeId == null || nodeId.isEmpty()) {
            return null;
        }
        
        try {
            NodesInfoResponse r = client.admin().cluster().nodesInfo(new NodesInfoRequest(nodeId).all()).actionGet();
            return r.getNodesMap().get(nodeId).getNode().getName();
        } catch (Exception e) {
            LOG.error("Could not read name of ES node.", e);
            return "UNKNOWN";
        }
        
    }
    
    public String nodeIdToHostName(String nodeId) {
        if (nodeId == null || nodeId.isEmpty()) {
            return null;
        }
        
        try {
            NodesInfoResponse r = client.admin().cluster().nodesInfo(new NodesInfoRequest(nodeId).all()).actionGet();
            return r.getNodesMap().get(nodeId).getHostname();
        } catch (Exception e) {
            LOG.error("Could not read name of ES node.", e);
            return "UNKNOWN";
        }
        
    }

    public boolean cycleAlias(String aliasName, String targetIndex) {
        return client.admin().indices().prepareAliases()
                .addAlias(targetIndex, aliasName)
                .execute().actionGet().isAcknowledged();
    }
    
    public boolean cycleAlias(String aliasName, String targetIndex, String oldIndex) {
        return client.admin().indices().prepareAliases()
                .removeAlias(oldIndex, aliasName)
                .addAlias(targetIndex, aliasName)
                .execute().actionGet().isAcknowledged();
    }

    public boolean bulkIndex(final List<Message> messages) {
        if (messages.isEmpty()) {
            return true;
        }

        final BulkRequestBuilder request = client.prepareBulk();
        for (Message msg : messages) {
            request.add(buildIndexRequest(Deflector.DEFLECTOR_NAME, msg.toElasticSearchObject(), msg.getId())); // Main index.
        }

        request.setConsistencyLevel(WriteConsistencyLevel.ONE);
        request.setReplicationType(ReplicationType.ASYNC);
        
        final BulkResponse response = client.bulk(request.request()).actionGet();
        
        LOG.debug("Deflector index: Bulk indexed {} messages, took {} ms, failures: {}",
                new Object[] { response.getItems().length, response.getTookInMillis(), response.hasFailures() });

        if (response.hasFailures()) {
            propagateFailure(response.getItems());
        }

        return !response.hasFailures();
    }

    private void propagateFailure(BulkItemResponse[] items) {
        LOG.error("Failed to index [{}] messages. Please check the index error log in your web interface for the reason.", items.length);

        boolean r = this.failureQueue.offer(items);

        if(!r) {
            LOG.debug("Could not propagate failure to failure queue. Queue is full.");
        }
    }

    public Searches searches() {
    	return searches;
    }
    
    public Counts counts() {
    	return counts;
    }
    
    public Messages messages() {
    	return messages;
    }
    
    public Cluster cluster() {
        return cluster;
    }

    public Indices indices() {
        return indices;
    }

    public LinkedBlockingQueue<BulkItemResponse[]> getFailureQueue() {
        return failureQueue;
    }

    private IndexRequestBuilder buildIndexRequest(String index, Map<String, Object> source, String id) {
        final IndexRequestBuilder b = new IndexRequestBuilder(client);

        b.setId(id);
        b.setSource(source);
        b.setIndex(index);
        b.setContentType(XContentType.JSON);
        b.setOpType(OpType.INDEX);
        b.setType(TYPE);
        b.setConsistencyLevel(WriteConsistencyLevel.ONE);

        return b;
    }

}
