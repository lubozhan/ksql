/**
 * Copyright 2017 Confluent Inc.
 **/
package io.confluent.kql.planner;

import io.confluent.kql.analyzer.AggregateAnalysis;
import io.confluent.kql.analyzer.Analysis;
import io.confluent.kql.metastore.KQLStream;
import io.confluent.kql.metastore.KQLTable;
import io.confluent.kql.metastore.KQLSTDOUT;
import io.confluent.kql.metastore.StructuredDataSource;
import io.confluent.kql.parser.tree.Expression;
import io.confluent.kql.planner.plan.AggregateNode;
import io.confluent.kql.planner.plan.FilterNode;
import io.confluent.kql.planner.plan.KQLConsoleOutputNode;
import io.confluent.kql.planner.plan.KQLStructuredDataOutputNode;
import io.confluent.kql.planner.plan.OutputNode;
import io.confluent.kql.planner.plan.PlanNode;
import io.confluent.kql.planner.plan.PlanNodeId;
import io.confluent.kql.planner.plan.ProjectNode;
import io.confluent.kql.planner.plan.SourceNode;
import io.confluent.kql.planner.plan.StructuredDataSourceNode;
import io.confluent.kql.util.ExpressionTypeManager;
import io.confluent.kql.util.SchemaUtil;

import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;

import java.util.ArrayList;
import java.util.List;

public class LogicalPlanner {

  Analysis analysis;
  AggregateAnalysis aggregateAnalysis;

  public LogicalPlanner(Analysis analysis, AggregateAnalysis aggregateAnalysis) {
    this.analysis = analysis;
    this.aggregateAnalysis = aggregateAnalysis;
  }

  public PlanNode buildPlan() {
    PlanNode currentNode;
    if (analysis.getJoin() != null) {
      currentNode = analysis.getJoin();
    } else {
      SourceNode sourceNode = buildSourceNode();
      currentNode = sourceNode;
    }
    if (analysis.getWhereExpression() != null) {
      FilterNode filterNode = buildFilterNode(currentNode.getSchema(), currentNode);
      currentNode = filterNode;
    }
    if ((analysis.getGroupByExpressions() != null) && (!analysis.getGroupByExpressions()
        .isEmpty())) {
      AggregateNode aggregateNode = buildAggregateNode(currentNode.getSchema(), currentNode);
      currentNode = aggregateNode;
    } else {
      ProjectNode projectNode = buildProjectNode(currentNode.getSchema(), currentNode);
      currentNode = projectNode;
    }

    OutputNode outputNode = buildOutputNode(currentNode.getSchema(), currentNode);
    return outputNode;
  }

  private OutputNode buildOutputNode(final Schema inputSchema, final PlanNode sourcePlanNode) {
    StructuredDataSource intoDataSource = analysis.getInto();

    if (intoDataSource instanceof KQLSTDOUT) {
      return new KQLConsoleOutputNode(new PlanNodeId(KQLSTDOUT.KQL_STDOUT_NAME), sourcePlanNode,
                                      inputSchema);
    } else if (intoDataSource instanceof StructuredDataSource) {
      StructuredDataSource intoStructuredDataSource = (StructuredDataSource) intoDataSource;

      return new KQLStructuredDataOutputNode(new PlanNodeId(intoDataSource.getName()),
                                             sourcePlanNode,
                                             inputSchema, intoStructuredDataSource.getKqlTopic(),
                                             intoStructuredDataSource.getKqlTopic()
                                                 .getTopicName());

    }
    throw new RuntimeException("INTO clause is not supported in SELECT.");
  }

  private AggregateNode buildAggregateNode(final Schema inputSchema,
                                           final PlanNode sourcePlanNode) {

    SchemaBuilder aggregateSchema = SchemaBuilder.struct();
    ExpressionTypeManager expressionTypeManager = new ExpressionTypeManager(inputSchema);
    for (int i = 0; i < analysis.getSelectExpressions().size(); i++) {
      Expression expression = analysis.getSelectExpressions().get(i);
      String alias = analysis.getSelectExpressionAlias().get(i);

      Schema expressionType = expressionTypeManager.getExpressionType(expression);

      aggregateSchema = aggregateSchema.field(alias, expressionType);

    }

    return new AggregateNode(new PlanNodeId("Aggregate"), sourcePlanNode, aggregateSchema,
                             analysis.getSelectExpressions(), analysis.getGroupByExpressions(),
                             analysis.getWindowExpression(),
                             aggregateAnalysis.getAggregateFunctionArguments(),
                             aggregateAnalysis.getFunctionList(),
                             aggregateAnalysis.getRequiredColumnsList(),
                             aggregateAnalysis.getNonAggResultColumns(),
                             aggregateAnalysis.getFinalSelectExpressions());
  }

  private ProjectNode buildProjectNode(final Schema inputSchema, final PlanNode sourcePlanNode) {
    List<Field> projectionFields = new ArrayList<>();
    List<String> fieldNames = new ArrayList<>();

    SchemaBuilder projectionSchema = SchemaBuilder.struct();
    ExpressionTypeManager expressionTypeManager = new ExpressionTypeManager(inputSchema);
    for (int i = 0; i < analysis.getSelectExpressions().size(); i++) {
      Expression expression = analysis.getSelectExpressions().get(i);
      String alias = analysis.getSelectExpressionAlias().get(i);

      Schema expressionType = expressionTypeManager.getExpressionType(expression);

      projectionSchema = projectionSchema.field(alias, expressionType);

    }

    return new ProjectNode(new PlanNodeId("Project"), sourcePlanNode, projectionSchema,
                           analysis.getSelectExpressions());
  }

  private FilterNode buildFilterNode(final Schema inputSchema, final PlanNode sourcePlanNode) {

    Expression filterExpression = analysis.getWhereExpression();
    return new FilterNode(new PlanNodeId("Filter"), sourcePlanNode, filterExpression);
  }

  private SourceNode buildSourceNode() {

    StructuredDataSource fromDataSource = analysis.getFromDataSources().get(0).getLeft();
    String alias = analysis.getFromDataSources().get(0).getRight();
    Schema fromSchema = SchemaUtil.buildSchemaWithAlias(fromDataSource.getSchema(), alias);

    if (fromDataSource instanceof KQLStream) {
      KQLStream fromStream = (KQLStream) fromDataSource;
      return new StructuredDataSourceNode(new PlanNodeId("KQLTopic"), fromSchema,
                                          fromDataSource.getKeyField(),
                                          fromStream.getKqlTopic().getTopicName(),
                                          alias, fromStream.getDataSourceType(),
                                          fromStream);
    } else if (fromDataSource instanceof KQLTable) {
      KQLTable fromTable = (KQLTable) fromDataSource;
      return new StructuredDataSourceNode(new PlanNodeId("KQLTopic"), fromSchema,
                                          fromDataSource.getKeyField(),
                                          fromTable.getKqlTopic().getTopicName(),
                                          alias, fromTable.getDataSourceType(),
                                          fromTable);
    }

    throw new RuntimeException("Data source is not supported yet.");
  }

}