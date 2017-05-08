/**
 * Copyright 2017 Confluent Inc.
 **/
package io.confluent.ksql;

import io.confluent.ksql.analyzer.AggregateAnalysis;
import io.confluent.ksql.analyzer.AggregateAnalyzer;
import io.confluent.ksql.analyzer.Analysis;
import io.confluent.ksql.analyzer.AnalysisContext;
import io.confluent.ksql.analyzer.Analyzer;
import io.confluent.ksql.metastore.KSQLStream;
import io.confluent.ksql.metastore.KSQLTable;
import io.confluent.ksql.metastore.KSQLTopic;
import io.confluent.ksql.metastore.MetaStore;
import io.confluent.ksql.metastore.MetaStoreImpl;
import io.confluent.ksql.metastore.StructuredDataSource;
import io.confluent.ksql.parser.rewrite.AggregateExpressionRewriter;
import io.confluent.ksql.parser.tree.Expression;
import io.confluent.ksql.parser.tree.ExpressionTreeRewriter;
import io.confluent.ksql.parser.tree.Query;
import io.confluent.ksql.parser.tree.Select;
import io.confluent.ksql.parser.tree.SelectItem;
import io.confluent.ksql.parser.tree.SingleColumn;
import io.confluent.ksql.physical.PhysicalPlanBuilder;
import io.confluent.ksql.planner.LogicalPlanner;
import io.confluent.ksql.planner.plan.KSQLBareOutputNode;
import io.confluent.ksql.planner.plan.KSQLStructuredDataOutputNode;
import io.confluent.ksql.planner.plan.OutputNode;
import io.confluent.ksql.planner.plan.PlanNode;
import io.confluent.ksql.structured.QueuedSchemaKStream;
import io.confluent.ksql.structured.SchemaKStream;
import io.confluent.ksql.structured.SchemaKTable;
import io.confluent.ksql.util.KSQLConfig;
import io.confluent.ksql.util.KSQLException;
import io.confluent.ksql.util.Pair;
import io.confluent.ksql.util.PersistentQueryMetadata;
import io.confluent.ksql.util.QueryMetadata;
import io.confluent.ksql.util.QueuedQueryMetadata;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.kstream.KStreamBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

public class QueryEngine {

  private KSQLConfig ksqlConfig;
  private final AtomicLong queryIdCounter;

  public QueryEngine(final KSQLConfig ksqlConfig) {
    this.ksqlConfig = ksqlConfig;
    this.queryIdCounter = new AtomicLong(1);
  }

  public List<Pair<String, PlanNode>> buildLogicalPlans(MetaStore metaStore, List<Pair<String, Query>> queryList) {

    List<Pair<String, PlanNode>> logicalPlansList = new ArrayList<>();
    MetaStore tempMetaStore = new MetaStoreImpl();
    for (String topicName : metaStore.getAllKSQLTopics().keySet()) {
      tempMetaStore.putTopic(metaStore.getTopic(topicName));
    }
    for (String dataSourceName : metaStore.getAllStructuredDataSourceNames()) {
      tempMetaStore.putSource(metaStore.getSource(dataSourceName));
    }

    for (Pair<String, Query> statementQueryPair : queryList) {
      Query query = statementQueryPair.getRight();
      // Analyze the query to resolve the references and extract operations
      Analysis analysis = new Analysis();
      Analyzer analyzer = new Analyzer(analysis, tempMetaStore);
      analyzer.process(query, new AnalysisContext(null, null));

      AggregateAnalysis aggregateAnalysis = new AggregateAnalysis();
      AggregateAnalyzer aggregateAnalyzer = new AggregateAnalyzer(aggregateAnalysis, metaStore);
      AggregateExpressionRewriter aggregateExpressionRewriter = new AggregateExpressionRewriter();
      for (Expression expression: analysis.getSelectExpressions()) {
        aggregateAnalyzer.process(expression, new AnalysisContext(null, null));
        if (!aggregateAnalyzer.isHasAggregateFunction()) {
          aggregateAnalysis.getNonAggResultColumns().add(expression);
        }
        aggregateAnalysis.getFinalSelectExpressions().add(ExpressionTreeRewriter.rewriteWith(aggregateExpressionRewriter, expression));
        aggregateAnalyzer.setHasAggregateFunction(false);
      }

      // TODO: make sure only aggregates are in the expression. For now we assume this is the case.
      if (analysis.getHavingExpression() != null) {
        aggregateAnalyzer.process(analysis.getHavingExpression(), new AnalysisContext(null, null));
        if (!aggregateAnalyzer.isHasAggregateFunction()) {
          aggregateAnalysis.getNonAggResultColumns().add(analysis.getHavingExpression());
        }
        aggregateAnalysis.setHavingExpression(ExpressionTreeRewriter.rewriteWith(aggregateExpressionRewriter,
                                                              analysis.getHavingExpression()));
        aggregateAnalyzer.setHasAggregateFunction(false);
      }

      // Build a logical plan
      PlanNode logicalPlan = new LogicalPlanner(analysis, aggregateAnalysis).buildPlan();
      if (logicalPlan instanceof KSQLStructuredDataOutputNode) {
        KSQLStructuredDataOutputNode ksqlStructuredDataOutputNode = (KSQLStructuredDataOutputNode) logicalPlan;
        StructuredDataSource
            structuredDataSource =
            new KSQLStream(ksqlStructuredDataOutputNode.getId().toString(),
                          ksqlStructuredDataOutputNode.getSchema(),
                          ksqlStructuredDataOutputNode.getKeyField(),
                          ksqlStructuredDataOutputNode.getKsqlTopic());

        tempMetaStore.putTopic(ksqlStructuredDataOutputNode.getKsqlTopic());
        tempMetaStore.putSource(structuredDataSource);
      }

      logicalPlansList.add(new Pair<>(statementQueryPair.getLeft(), logicalPlan));
    }
    return logicalPlansList;
  }

  private StructuredDataSource getPlanDataSource(PlanNode outputNode) {
    KSQLTopic ksqlTopic = new KSQLTopic(outputNode.getId().toString(), outputNode.getId().toString(), null);
    return new KSQLStream(outputNode.getId().toString(), outputNode.getSchema(), outputNode.getKeyField(), ksqlTopic);
  }

  public List<QueryMetadata> buildPhysicalPlans(
      boolean addUniqueTimeSuffix,
      MetaStore metaStore,
      List<Pair<String, PlanNode>> logicalPlans
  ) throws Exception {

    List<QueryMetadata> physicalPlans = new ArrayList<>();

    for (Pair<String, PlanNode> statementPlanPair : logicalPlans) {

      PlanNode logicalPlan = statementPlanPair.getRight();
      KStreamBuilder builder = new KStreamBuilder();

      //Build a physical plan, in this case a Kafka Streams DSL
      PhysicalPlanBuilder physicalPlanBuilder = new PhysicalPlanBuilder(builder);
      SchemaKStream schemaKStream = physicalPlanBuilder.buildPhysicalPlan(logicalPlan);

      OutputNode outputNode = physicalPlanBuilder.getPlanSink();
      boolean isBareQuery = outputNode instanceof KSQLBareOutputNode;

      // Check to make sure the logical and physical plans match up; important to do this BEFORE actually starting up
      // the corresponding Kafka Streams job
      if (isBareQuery && !(schemaKStream instanceof QueuedSchemaKStream)) {
        throw new Exception(String.format(
            "Mismatch between logical and physical output; expected a QueuedSchemaKStream based on logical "
                + "KSQLBareOutputNode, found a %s instead",
            schemaKStream.getClass().getCanonicalName()
        ));
      }

      if (isBareQuery) {
        String applicationId = getBareQueryApplicationId();
        if (addUniqueTimeSuffix) {
          applicationId = addTimeSuffix(applicationId);
        }

        KafkaStreams streams = buildStreams(builder, applicationId);

        QueuedSchemaKStream queuedSchemaKStream = (QueuedSchemaKStream) schemaKStream;
        KSQLBareOutputNode ksqlBareOutputNode = (KSQLBareOutputNode) outputNode;
        physicalPlans.add(new QueuedQueryMetadata(
            statementPlanPair.getLeft(),
            streams,
            ksqlBareOutputNode,
            queuedSchemaKStream.getQueue()
        ));

      } else if (outputNode instanceof KSQLStructuredDataOutputNode) {
        long queryId = getNextQueryId();
        String applicationId = String.format("ksql_query_%d", queryId);
        if (addUniqueTimeSuffix) {
          applicationId = addTimeSuffix(applicationId);
        }

        KafkaStreams streams = buildStreams(builder, applicationId);

        KSQLStructuredDataOutputNode kafkaTopicOutputNode = (KSQLStructuredDataOutputNode) outputNode;
        physicalPlans.add(
            new PersistentQueryMetadata(statementPlanPair.getLeft(), streams, kafkaTopicOutputNode, queryId)
        );
        if (metaStore.getTopic(kafkaTopicOutputNode.getKafkaTopicName()) == null) {
          metaStore.putTopic(kafkaTopicOutputNode.getKsqlTopic());
        }
        StructuredDataSource sinkDataSource;
        if (schemaKStream instanceof SchemaKTable) {
          SchemaKTable schemaKTable = (SchemaKTable) schemaKStream;
          sinkDataSource =
              new KSQLTable(kafkaTopicOutputNode.getId().toString(),
                  kafkaTopicOutputNode.getSchema(),
                  schemaKStream.getKeyField(),
                  kafkaTopicOutputNode.getKsqlTopic(), kafkaTopicOutputNode.getId()
                  .toString() + "_statestore",
                  schemaKTable.isWindowed());
        } else {
          sinkDataSource =
              new KSQLStream(kafkaTopicOutputNode.getId().toString(),
                  kafkaTopicOutputNode.getSchema(),
                  schemaKStream.getKeyField(),
                  kafkaTopicOutputNode.getKsqlTopic());
        }

        metaStore.putSource(sinkDataSource);
      } else {
        throw new KSQLException("Sink data source is not correct.");
      }

    }
    return physicalPlans;
  }

  public StructuredDataSource getResultDatasource(final Select select, final String name) {

    SchemaBuilder dataSource = SchemaBuilder.struct().name(name);

    for (SelectItem selectItem : select.getSelectItems()) {
      if (selectItem instanceof SingleColumn) {
        SingleColumn singleColumn = (SingleColumn) selectItem;
        String fieldName = singleColumn.getAlias().get();
        dataSource = dataSource.field(fieldName, Schema.BOOLEAN_SCHEMA);
      }
    }

    KSQLTopic ksqlTopic = new KSQLTopic(name, name, null);
    return new KSQLStream(name, dataSource.schema(), dataSource.fields().get(0), ksqlTopic);
  }

  private KafkaStreams buildStreams(KStreamBuilder builder, String applicationId) {
    Map<String, Object> streamsProperties = ksqlConfig.getResetStreamsProperties(applicationId);
    return new KafkaStreams(builder, new StreamsConfig(streamsProperties));
  }

  private long getNextQueryId() {
    return queryIdCounter.getAndIncrement();
  }

  // TODO: This should probably be changed
  private String getBareQueryApplicationId() {
    return String.format("ksql_bare_query_%d", Math.abs(ThreadLocalRandom.current().nextLong()));
  }

  private String addTimeSuffix(String original) {
    return String.format("%s_%d", original, System.currentTimeMillis());
  }
}