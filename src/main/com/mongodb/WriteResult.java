// WriteResult.java

package com.mongodb;


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


/**
 * This class lets you access the results of the previous write
 * if you have STRICT mode on, this just stores the result of that getLastError call
 * if you don't, then this will actually to the getlasterror call.  
 * if another op has been done on this connection in the interim, this will fail
 */
public class WriteResult {
    
    WriteResult( CommandResult o ){
        _lastErrorResult = o;
        _lazy = false;
    }
    
    WriteResult( DB db , DBPort p ){
        _db = db;
        _port = p;
        _lastCall = p._calls;
        _lazy = true;
    }
    
    public synchronized CommandResult getLastError(){
        if ( _lastErrorResult != null )
            return _lastErrorResult;
        
        if ( _port != null ){
            _lastErrorResult = _port.tryGetLastError( _db , _lastCall );
            _port = null;
            _db = null;
        }
        
        if ( _lastErrorResult == null )
            throw new IllegalStateException( "this port has been used since the last call, can't call getLastError anymore" );
        
        return _lastErrorResult;
    }


    public String getError(){
        Object foo = getField( "err" );
        if ( foo == null )
            return null;
        return foo.toString();
    }
    
    public int getN(){
        return getLastError().getInt( "n" );
    }
    
    public Object getField( String name ){
        return getLastError().get( name );
    }

    public boolean isLazy(){
        return _lazy;
    }

    public String toString(){
        return getLastError().toString();
    }

    DB _db;
    DBPort _port;
    long _lastCall;

    CommandResult _lastErrorResult;

    final boolean _lazy;
}
