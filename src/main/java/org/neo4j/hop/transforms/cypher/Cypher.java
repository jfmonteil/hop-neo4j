package org.neo4j.hop.transforms.cypher;


import org.neo4j.hop.shared.MetaStoreUtil;
import org.neo4j.hop.shared.NeoConnection;
import org.neo4j.hop.shared.NeoConnectionUtils;
import org.apache.commons.lang.StringUtils;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.TransactionWork;
import org.neo4j.driver.Value;
import org.neo4j.driver.exceptions.ServiceUnavailableException;
import org.neo4j.driver.summary.Notification;
import org.neo4j.driver.summary.ResultSummary;
import org.neo4j.hop.core.data.GraphData;
import org.neo4j.hop.core.data.GraphPropertyDataType;
import org.neo4j.hop.model.GraphPropertyType;
import org.apache.hop.core.Const;
import org.apache.hop.core.exception.HopException;
import org.apache.hop.core.exception.HopTransformException;
import org.apache.hop.core.row.RowDataUtil;
import org.apache.hop.core.row.RowMeta;
import org.apache.hop.core.row.IValueMeta;
import org.apache.hop.pipeline.Pipeline;
import org.apache.hop.pipeline.PipelineMeta;
import org.apache.hop.pipeline.transform.BaseTransform;
import org.apache.hop.pipeline.transform.ITransform;
import org.apache.hop.pipeline.transform.TransformMeta;
import org.apache.hop.metastore.api.exceptions.MetaStoreException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Cypher extends BaseTransform<CypherMeta, CypherData> implements ITransform<CypherMeta, CypherData> {

  public Cypher( TransformMeta transformMeta, CypherMeta meta, CypherData data, int copyNr,
                 PipelineMeta pipelineMeta, Pipeline pipeline ) {
    super( transformMeta, meta, data, copyNr, pipelineMeta, pipeline );
  }


  @Override public boolean init() {

    // Is the step getting input?
    //
    List<TransformMeta> steps = getPipelineMeta().findPreviousTransforms( getTransformMeta() );
    data.hasInput = steps != null && steps.size() > 0;

    // Connect to Neo4j
    //
    if ( StringUtils.isEmpty( meta.getConnectionName() ) ) {
      log.logError( "You need to specify a Neo4j connection to use in this step" );
      return false;
    }
    try {

      data.neoConnection = NeoConnection.createFactory( metaStore ).loadElement( meta.getConnectionName() );
      if (data.neoConnection==null) {
        log.logError("Connection '"+meta.getConnectionName()+"' could not be found in the metastore "+MetaStoreUtil.getMetaStoreDescription(metaStore));
        return false;
      }
      data.neoConnection.initializeVariablesFrom( this );

    } catch ( MetaStoreException e ) {
      log.logError( "Could not gencsv Neo4j connection '" + meta.getConnectionName() + "' from the metastore", e );
      return false;
    }

    data.batchSize = Const.toLong( environmentSubstitute( meta.getBatchSize() ), 1 );

    try {
      createDriverSession();
    } catch ( Exception e ) {
      log.logError( "Unable to get or create Neo4j database driver for database '" + data.neoConnection.getName() + "'", e );
      return false;
    }

    return super.init();
  }

  @Override public void dispose( ) {

    wrapUpTransaction();
    closeSessionDriver();

    super.dispose();
  }

  private void closeSessionDriver() {
    if ( data.session != null ) {
      data.session.close();
    }
  }

  private void createDriverSession() {
    data.session = data.neoConnection.getSession(log);
  }

  private void reconnect() {
    closeSessionDriver();

    log.logBasic( "RECONNECTING to database" );

    // Wait for 30 seconds before reconnecting.
    // Let's give the server a breath of fresh air.
    try {
      Thread.sleep( 30000 );
    } catch ( InterruptedException e ) {
      // ignore sleep interrupted.
    }

    createDriverSession();
  }

  @Override public boolean processRow() throws HopException {

    // Input row
    //
    Object[] row = new Object[ 0 ];

    // Only if we actually have previous steps to read from...
    // This way the step also acts as an GraphOutput query step
    //
    if ( data.hasInput ) {
      // Get a row of data from previous steps...
      //
      row = getRow();
      if ( row == null ) {

        // See if there's anything left to write...
        //
        wrapUpTransaction();

        // Signal next step(s) we're done processing
        //
        setOutputDone();
        return false;
      }
    }

    if ( first ) {
      first = false;

      // get the output fields...
      //
      data.outputRowMeta = data.hasInput ? getInputRowMeta().clone() : new RowMeta();
      meta.getFields( data.outputRowMeta, getTransformName(), null, getTransformMeta(), this, metaStore );

      // Create a session
      //
      createDriverSession();

      // Get parameter field indexes
      data.fieldIndexes = new int[ meta.getParameterMappings().size() ];
      for ( int i = 0; i < meta.getParameterMappings().size(); i++ ) {
        String field = meta.getParameterMappings().get( i ).getField();
        data.fieldIndexes[ i ] = getInputRowMeta().indexOfValue( field );
        if ( data.fieldIndexes[ i ] < 0 ) {
          throw new HopTransformException( "Unable to find parameter field '" + field );
        }
      }

      data.cypherFieldIndex = -1;
      if ( data.hasInput ) {
        data.cypherFieldIndex = getInputRowMeta().indexOfValue( meta.getCypherField() );
        if ( meta.isCypherFromField() && data.cypherFieldIndex < 0 ) {
          throw new HopTransformException( "Unable to find cypher field '" + meta.getCypherField() + "'" );
        }
      }
      data.cypher = environmentSubstitute( meta.getCypher() );

      data.unwindList = new ArrayList<>();
      data.unwindMapName = environmentSubstitute( meta.getUnwindMapName() );

      data.cypherStatements = new ArrayList<>();
    }

    if ( meta.isCypherFromField() ) {
      data.cypher = getInputRowMeta().getString( row, data.cypherFieldIndex );
    }

    // Do the value mapping and conversion to the parameters
    //
    Map<String, Object> parameters = new HashMap<>();
    for ( int i = 0; i < meta.getParameterMappings().size(); i++ ) {
      ParameterMapping mapping = meta.getParameterMappings().get( i );
      IValueMeta valueMeta = getInputRowMeta().getValueMeta( data.fieldIndexes[ i ] );
      Object valueData = row[ data.fieldIndexes[ i ] ];
      GraphPropertyType propertyType = GraphPropertyType.parseCode( mapping.getNeoType() );
      if ( propertyType == null ) {
        throw new HopException( "Unable to convert to unknown property type for field '" + valueMeta.toStringMeta() + "'" );
      }
      Object neoValue = propertyType.convertFromHop( valueMeta, valueData );
      parameters.put( mapping.getParameter(), neoValue );
    }

    // Create a map between the return value and the source type so we can do the appropriate mapping later...
    //
    data.returnSourceTypeMap = new HashMap<>(  );
    for (ReturnValue returnValue : meta.getReturnValues()) {
      if (StringUtils.isNotEmpty( returnValue.getSourceType() )) {
        String name = returnValue.getName();
        GraphPropertyDataType type = GraphPropertyDataType.parseCode( returnValue.getSourceType() );
        data.returnSourceTypeMap.put(name, type);
      }
    }

    if ( meta.isUsingUnwind() ) {
      data.unwindList.add( parameters );
      data.outputCount++;

      if ( data.outputCount >= data.batchSize ) {
        writeUnwindList();
      }
    } else {

      // Execute the cypher with all the parameters...
      //
      try {
        runCypherStatement( row, data.cypher, parameters );
      } catch ( ServiceUnavailableException e ) {
        // retry once after reconnecting.
        // This can fix certain time-out issues
        //
        if (meta.isRetrying()) {
          reconnect();
          runCypherStatement( row, data.cypher, parameters );
        } else {
          throw e;
        }
      } catch(HopException e) {
        setErrors( 1 );
        stopAll();
        throw e;
      }
    }

    // Only keep executing if we have input rows...
    //
    if ( data.hasInput ) {
      return true;
    } else {
      setOutputDone();
      return false;
    }
  }

  private void runCypherStatement( Object[] row, String cypher, Map<String, Object> parameters ) throws HopException {
    data.cypherStatements.add( new CypherStatement( row, cypher, parameters ) );
    if ( data.cypherStatements.size() >= data.batchSize || !data.hasInput) {
      runCypherStatementsBatch();
    }
  }

  private void runCypherStatementsBatch() throws HopException {

    if (data.cypherStatements==null || data.cypherStatements.size()==0) {
      // Nothing to see here, move along
      return;
    }

    // Execute all the statements in there in one transaction...
    //
    TransactionWork<Integer> transactionWork = transaction -> {

      for ( CypherStatement cypherStatement : data.cypherStatements ) {
        Result result = transaction.run( cypherStatement.getCypher(), cypherStatement.getParameters() );
        try {
          getResultRows( result, cypherStatement.getRow(), false );


        } catch(Exception e) {
          throw new RuntimeException( "Error parsing result of cypher statement '"+cypherStatement.getCypher()+"'", e );
        }
      }

      return data.cypherStatements.size();
    };

    try {
      int nrProcessed;
      if ( meta.isReadOnly() ) {
        nrProcessed = data.session.readTransaction( transactionWork );
        setLinesInput( getLinesInput() + data.cypherStatements.size() );
      } else {
        nrProcessed = data.session.writeTransaction( transactionWork );
        setLinesOutput( getLinesOutput() + data.cypherStatements.size() );
      }

      if (log.isDebug()) {
        logDebug( "Processed "+nrProcessed+" statements" );
      }

      // Clear out the batch of statements.
      //
      data.cypherStatements.clear();

    } catch ( Exception e ) {
      throw new HopException( "Unable to execute batch of cypher statements ("+data.cypherStatements.size()+")", e );
    }
  }

  private List<Object[]> writeUnwindList() throws HopException {
    HashMap<String, Object> unwindMap = new HashMap<>();
    unwindMap.put( data.unwindMapName, data.unwindList );
    List<Object[]> resultRows = null;
    CypherTransactionWork cypherTransactionWork = new CypherTransactionWork( this, new Object[ 0 ], true, data.cypher, unwindMap );
    try {
      try {
        if ( meta.isReadOnly() ) {
          data.session.readTransaction( cypherTransactionWork );
        } else {
          data.session.writeTransaction( cypherTransactionWork );
        }
      } catch ( ServiceUnavailableException e ) {
        // retry once after reconnecting.
        // This can fix certain time-out issues
        //
        if (meta.isRetrying()) {
          reconnect();
          if ( meta.isReadOnly() ) {
            data.session.readTransaction( cypherTransactionWork );
          } else {
            data.session.writeTransaction( cypherTransactionWork );
          }
        } else {
          throw e;
        }
      }
    } catch ( Exception e ) {
      data.session.close();
      stopAll();
      setErrors( 1L );
      setOutputDone();
      throw new HopException( "Unexpected error writing unwind list to Neo4j", e );
    }
    setLinesOutput( getLinesOutput() + data.unwindList.size() );
    data.unwindList.clear();
    data.outputCount = 0;
    return resultRows;
  }

  public void getResultRows( Result result, Object[] row, boolean unwind ) throws HopException {

    if ( result != null ) {

      if ( meta.isReturningGraph() ) {

        GraphData graphData = new GraphData( result );
        graphData.setSourceTransformationName( getPipelineMeta().getName() );
        graphData.setSourceStepName( getTransformName() );

        // Create output row
        Object[] outputRowData;
        if ( unwind ) {
          outputRowData = RowDataUtil.allocateRowData( data.outputRowMeta.size() );
        } else {
          outputRowData = RowDataUtil.createResizedCopy( row, data.outputRowMeta.size() );
        }
        int index = data.hasInput && !unwind ? getInputRowMeta().size() : 0;

        outputRowData[ index ] = graphData;
        putRow( data.outputRowMeta, outputRowData );

      } else {

        while ( result.hasNext() ) {
          Record record = result.next();

          // Create output row
          Object[] outputRow;
          if ( unwind ) {
            outputRow = RowDataUtil.allocateRowData( data.outputRowMeta.size() );
          } else {
            outputRow = RowDataUtil.createResizedCopy( row, data.outputRowMeta.size() );
          }

          // add result values...
          //
          int index = data.hasInput && !unwind ? getInputRowMeta().size() : 0;
          for ( ReturnValue returnValue : meta.getReturnValues() ) {
            Value recordValue = record.get( returnValue.getName() );
            IValueMeta targetValueMeta = data.outputRowMeta.getValueMeta( index );
            Object value = null;
            if ( recordValue != null && !recordValue.isNull()) {
              try {
                switch ( targetValueMeta.getType() ) {
                  case IValueMeta.TYPE_STRING:
                    value = recordValue.asString();
                    break;
                  case IValueMeta.TYPE_INTEGER:
                    value = recordValue.asLong();
                    break;
                  case IValueMeta.TYPE_NUMBER:
                    value = recordValue.asDouble();
                    break;
                  case IValueMeta.TYPE_BOOLEAN:
                    value = recordValue.asBoolean();
                    break;
                  case IValueMeta.TYPE_BIGNUMBER:
                    value = new BigDecimal( recordValue.asString() );
                    break;
                  case IValueMeta.TYPE_DATE:
                    GraphPropertyDataType type = data.returnSourceTypeMap.get( returnValue.getName() );
                    if (type!=null) {
                     // Standard...
                     switch(type) {
                       case LocalDateTime: {
                         LocalDateTime localDateTime = recordValue.asLocalDateTime();
                         value = java.sql.Date.valueOf( localDateTime.toLocalDate() );
                         break;
                       }
                       case Date: {
                         LocalDate localDate = recordValue.asLocalDate();
                         value = java.sql.Date.valueOf( localDate );
                         break;
                       }
                       default:
                         throw new HopException( "Conversion from Neo4j daa type "+type.name()+" to a Hop Date isn't supported yet" );
                     }
                    } else {
                      LocalDate localDate = recordValue.asLocalDate();
                      value = java.sql.Date.valueOf( localDate );
                    }
                    break;
                  case IValueMeta.TYPE_TIMESTAMP:
                    LocalDateTime localDateTime = recordValue.asLocalDateTime();
                    value = java.sql.Timestamp.valueOf( localDateTime );
                    break;
                  default:
                    throw new HopException( "Unable to convert Neo4j data to type " + targetValueMeta.toStringMeta() );
                }
              } catch ( Exception e ) {
                throw new HopException(
                  "Unable to convert Neo4j record value '" + returnValue.getName() + "' to type : " + targetValueMeta.getTypeDesc(), e );
              }
            }
            outputRow[ index++ ] = value;
          }

          // Pass the rows to the next steps
          //
          putRow( data.outputRowMeta, outputRow );
        }
      }

      // Now that all result rows are consumed we can evaluate the result summary.
      //
      if ( processSummary( result ) ) {
        setErrors( 1L );
        stopAll();
        setOutputDone();
        throw new HopException( "Error found in executing cypher statement" );
      }
    }
  }

  private boolean processSummary( Result result ) {
    boolean error = false;
    ResultSummary summary = result.consume();
    for ( Notification notification : summary.notifications() ) {
      log.logError( notification.title() + " (" + notification.severity() + ")" );
      log.logError( notification.code() + " : " + notification.description() + ", position " + notification.position() );
      error = true;
    }
    return error;
  }

  @Override public void batchComplete() {

    try {
      wrapUpTransaction();
    } catch(Exception e) {
      setErrors( getErrors()+1 );
      stopAll();
      throw new RuntimeException( "Unable to complete batch of records", e );
    }

  }

  private void wrapUpTransaction() {

    if (!isStopped()) {
      try {
        if ( meta.isUsingUnwind() && data.unwindList != null ) {
          if ( data.unwindList.size() > 0 ) {
            writeUnwindList();
          }
        } else {
          // See if there are statements left to execute...
          //
          if (data.cypherStatements!=null && data.cypherStatements.size()>0) {
            runCypherStatementsBatch();
          }
        }
      } catch ( Exception e ) {
        setErrors( getErrors() + 1 );
        stopAll();
        throw new RuntimeException( "Unable to run batch of cypher statements", e );
      }
    }

    // At the end of each batch, do a commit.
    //
    if ( data.outputCount > 0 ) {

      // With UNWIND we don't have to end a transaction
      //
      if ( data.transaction != null ) {
        if ( getErrors() == 0 ) {
          data.transaction.commit();
        } else {
          data.transaction.rollback();
        }
        data.transaction.close();
      }
      data.outputCount = 0;
    }
  }
}