// DBPortPool.java

/**
 *      Copyright (C) 2008 10gen Inc.
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package com.mongodb;

import com.mongodb.util.*;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;

import java.lang.management.*;
import javax.management.*;

class DBPortPool extends SimplePool<DBPort> {

    static class Holder {
        
        Holder( MongoOptions options ){
            _options = options;
        }
        
        DBPortPool get( InetSocketAddress addr ){
            
            DBPortPool p = _pools.get( addr );
            
            if (p != null) 
                return p;
            
            synchronized (_pools) {
                p = _pools.get( addr );
                if (p != null) {
                    return p;
                }
                
                p = new DBPortPool( addr , _options );
                _pools.put( addr , p);
                String name = "com.mongodb:type=ConnectionPool,host=" + addr.toString().replace( ':' , '_' );
                
                try {
                    ObjectName on = new ObjectName( name );
                    if ( _server.isRegistered( on ) ){
                        _server.unregisterMBean( on );
                        Bytes.LOGGER.log( Level.INFO , "multiple Mongo instances for same host, jmx numbers might be off" );
                    }
                    _server.registerMBean( p , on );
                }
                catch ( JMException e ){
                    Bytes.LOGGER.log( Level.WARNING , "jmx registration error, continuing" , e );
                }
                catch ( java.security.AccessControlException e ){
                    Bytes.LOGGER.log( Level.WARNING , "jmx registration error, continuing" , e );
                }

            }
            
            return p;
        }

        void close(){
            synchronized ( _pools ){
                for ( DBPortPool p : _pools.values() ){
                    p.close();
                }
            }
        }
        
        final MongoOptions _options;
        final Map<InetSocketAddress,DBPortPool> _pools = Collections.synchronizedMap( new HashMap<InetSocketAddress,DBPortPool>() );
        final MBeanServer _server = ManagementFactory.getPlatformMBeanServer();
    }

    // ----
    
    public static class NoMoreConnection extends MongoInternalException {
	NoMoreConnection( String msg ){
	    super( msg );
	}
    }
    
    public static class SemaphoresOut extends NoMoreConnection {
        SemaphoresOut(){
            super( "Out of semaphores to get db connection" );
        }
    }

    public static class ConnectionWaitTimeOut extends NoMoreConnection {
        ConnectionWaitTimeOut(int timeout) {
            super("Connection wait timeout after " + timeout + " ms");
        }
    }

    // ----
    
    DBPortPool( InetSocketAddress addr , MongoOptions options ){
        super( "DBPortPool-" + addr.toString() , options.connectionsPerHost , options.connectionsPerHost );
        _options = options;
        _addr = addr;
	_waitingSem = new Semaphore( _options.connectionsPerHost * _options.threadsAllowedToBlockForConnectionMultiplier );
    }

    protected long memSize( DBPort p ){
        return 0;
    }

    protected int pick( int iThink , boolean couldCreate ){
        final int id = Thread.currentThread().hashCode();
        final int s = _availSafe.size();
        for ( int i=0; i<s; i++ ){
            DBPort p = _availSafe.get(i);
            if ( p._lastThread == id )
                return i;
        }

        if ( couldCreate )
            return -1;
        return iThink;
    }
    
    public DBPort get(){
	DBPort port = null;
	if ( ! _waitingSem.tryAcquire() )
	    throw new SemaphoresOut();

	try {
	    port = get( _options.maxWaitTime );
	}
	finally {
	    _waitingSem.release();
	}

	if ( port == null )
	    throw new ConnectionWaitTimeOut( _options.maxWaitTime );
	
        port._lastThread = Thread.currentThread().hashCode();
	return port;
    }

    void gotError( Exception e ){
        if ( e instanceof java.nio.channels.ClosedByInterruptException || 
             e instanceof InterruptedException ){
            // this is probably a request that is taking too long
            // so usually doesn't mean there is a real db problem
            return;
        }
        
        if ( e instanceof java.net.SocketTimeoutException && _options.socketTimeout > 0 ){
            // we don't want to clear the port pool for 1 connection timing out
            return;
        }
        
        // We don't want to clear the entire pool for the occasional error.
        if ( e instanceof SocketException) {
        	if (recentFailures < ALLOWED_ERRORS_BEFORE_CLEAR) {
        		return;
        	}
        }
        
        Bytes.LOGGER.log( Level.INFO , "emptying DBPortPool b/c of error" , e );
        clear();
    }

    void close(){
        clear();
    }

    public void cleanup( DBPort p ){
        p.close();
    }

    public boolean ok( DBPort t ){
        return _addr.equals( t._addr );
    }
    
    protected DBPort createNew()
        throws MongoInternalException{
        try {
            return new DBPort( _addr , this , _options );
        }
        catch ( IOException ioe ){
            throw new MongoInternalException( "can't create port to:" + _addr , ioe );
        }
    }
    

	public int getRecentFailures() {
		return recentFailures;
	}

	public void incrementRecentFailures() {
		_logger.warning("Failure recorded:" + _addr.toString());
		this.recentFailures++;
	}
	
	public void resetRecentFailures() {
		if (this.recentFailures > 0) {
			_logger.warning("Successful Request. Reseting recent failures:" + _addr.toString());
		}
		
		this.recentFailures = 0;
	}

    final MongoOptions _options;
    final private Semaphore _waitingSem;
    final InetSocketAddress _addr;
    boolean _everWorked = false;
    public final static Integer ALLOWED_ERRORS_BEFORE_CLEAR = Integer.valueOf(System.getProperty("MONGO.ERRORS_BEFORE_CLEAR", "5"));
    private Logger _logger = Logger.getLogger( DBPortPool.class.toString() );

    /**
     * The number of failures that this port pool has recently experienced.
     */
    private int recentFailures = 0;

}
