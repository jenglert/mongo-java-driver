// DBTCPConnector.java

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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

class DBTCPConnector implements DBConnector {

	static Logger _logger = Logger.getLogger(Bytes.LOGGER.getName() + ".tcp");
	static Logger _createLogger = Logger.getLogger(_logger.getName()
			+ ".connect");

	public DBTCPConnector(Mongo m, ServerAddress addr) throws MongoException {
		_mongo = m;
		_portHolder = new DBPortPool.Holder(m._options);
		_checkAddress(addr);

		_createLogger.info(addr.toString());

		if (addr.isPaired()) {
			_allHosts = new ArrayList<ServerAddress>(addr.explode());
			_createLogger.info("switch to paired mode : " + _allHosts + " -> "
					+ _curAddress);
		} else {
			_set(addr);
			_allHosts = null;
		}

	}

	public DBTCPConnector(Mongo m, ServerAddress... all) throws MongoException {
		this(m, Arrays.asList(all));
	}

	public DBTCPConnector(Mongo m, List<ServerAddress> all)
			throws MongoException {
		_mongo = m;
		_portHolder = new DBPortPool.Holder(m._options);
		_checkAddress(all);

		_allHosts = new ArrayList<ServerAddress>(all); // make a copy so it
														// can't be modified

		_createLogger.info(all + " -> " + _curAddress);
	}

	private static ServerAddress _checkAddress(ServerAddress addr) {
		if (addr == null)
			throw new NullPointerException("address can't be null");
		return addr;
	}

	private static ServerAddress _checkAddress(List<ServerAddress> addrs) {
		if (addrs == null)
			throw new NullPointerException("addresses can't be null");
		if (addrs.size() == 0)
			throw new IllegalArgumentException(
					"need to specify at least 1 address");
		return addrs.get(0);
	}

	/**
	 * Start a "request".
	 * 
	 * A "request" is a group of operations in which order matters. Examples
	 * include inserting a document and then performing a query which expects
	 * that document to have been inserted, or performing an operation and then
	 * using com.mongodb.Mongo.getLastError to perform error-checking on that
	 * operation. When a thread performs operations in a "request", all
	 * operations will be performed on the same socket, so they will be
	 * correctly ordered.
	 */
	public void requestStart() {
		_threadPort.get().requestStart();
	}

	/**
	 * End the current "request", if this thread is in one.
	 * 
	 * By ending a request when it is safe to do so the built-in connection-
	 * pool is allowed to reassign requests to different sockets in order to
	 * more effectively balance load. See requestStart for more information.
	 */
	public void requestDone() {
		_threadPort.get().requestDone();
	}

	public void requestEnsureConnection() {
		_threadPort.get().requestEnsureConnection();
	}

	WriteResult _checkWriteError(MyPort mp, DBPort port) throws MongoException {

		CommandResult e = _mongo.getDB("admin").getLastError();
		mp.done(port);

		Object foo = e.get("err");
		if (foo == null)
			return new WriteResult(e);

		int code = -1;
		if (e.get("code") instanceof Number)
			code = ((Number) e.get("code")).intValue();
		String s = foo.toString();
		if (code == 11000 || code == 11001 || s.startsWith("E11000")
				|| s.startsWith("E11001"))
			throw new MongoException.DuplicateKey(code, s);
		throw new MongoException(code, s);
	}

	public WriteResult say(DB db, OutMessage m, DB.WriteConcern concern)
			throws MongoException {
		MyPort mp = _threadPort.get();
		DBPort port = mp.get(true);
		port.checkAuth(db);

		try {
			port.say(m);

			// Successful write.  Reset failure count.
			_curPortPool.resetRecentFailures();
			
			if (concern == DB.WriteConcern.STRICT) {
				return _checkWriteError(mp, port);
			} else {
				mp.done(port);
				return new WriteResult(db, port);
			}
		} catch (IOException ioe) {
			mp.error(ioe);
			_error(ioe);
			if (concern == DB.WriteConcern.NONE) {
				CommandResult res = new CommandResult();
				res.put("ok", false);
				res.put("$err", "NETWORK ERROR");
				return new WriteResult(res);
			}
			throw new MongoException.Network("can't say something", ioe);
		} catch (MongoException me) {
			throw me;
		} catch (RuntimeException re) {
			mp.error(re);
			throw re;
		}

	}

	public Response call(DB db, DBCollection coll, OutMessage m)
			throws MongoException {
		return call(db, coll, m, 2);
	}

	public Response call(DB db, DBCollection coll, OutMessage m, int retries)
			throws MongoException {

		final MyPort mp = _threadPort.get();
		final DBPort port = mp.get(false);

		port.checkAuth(db);

		Response res = null;
		try {
			res = port.call(m, coll);
			
			// Successful write.  Reset failure count.
			_curPortPool.resetRecentFailures();
			
			mp.done(port);
		} catch (IOException ioe) {
			mp.error(ioe);
			if (_error(ioe) && retries > 0) {
				return call(db, coll, m, retries - 1);
			}
			throw new MongoException.Network("can't call something", ioe);
		} catch (RuntimeException re) {
			mp.error(re);
			throw re;
		}

		ServerError err = res.getError();

		if (err != null && err.isNotMasterError()) {
			_pickCurrent();
			if (retries <= 0) {
				throw new MongoException(
						"not talking to master and retries used up");
			}
			return call(db, coll, m, retries - 1);
		}

		return res;
	}

	public ServerAddress getAddress() {
		return _curAddress;
	}

	public List<ServerAddress> getAllAddress() {
		return _allHosts;
	}

	public String getConnectPoint() {
		return _curAddress.toString();
	}

	boolean _error(Throwable t) throws MongoException {

		// Increment the number of recent failures for the port pool.
		_curPortPool.incrementRecentFailures();

		if (_allHosts != null) {
			System.out.println("paired mode, switching master b/c of: " + t);
			t.printStackTrace();
			_pickCurrent();
		}
		return true;
	}

	class MyPort {

		DBPort get(boolean keep) {
			_internalStack++;

			if (_internalStack > 1) {
				if (_last == null) {
					System.err.println("_internalStack > 1 and _last is null!");
				} else {
					return _last;
				}
			}

			if (_port != null)
				return _port;

			try {
				DBPort p = _curPortPool.get();
				if (keep && _inRequest)
					_port = p;

				_last = p;
				return p;
			} catch (DBPortPool.NoMoreConnection nmc) {
				_internalStack = 0;
				throw nmc;
			}
		}

		void done(DBPort p) {

			if (_internalStack <= 0) {
				int prev = _internalStack;
				_reset();
				throw new IllegalStateException(
						"done called and _internalStack was: " + _internalStack);
			}

			_internalStack--;

			if (p != _port && _internalStack == 0)
				_curPortPool.done(p);

			if (_internalStack < 0) {
				System.err.println("_internalStack < 0 : " + _internalStack);
				_internalStack = 0;
			}
		}

		void error(Exception e) {
			_curPortPool.remove(_port);
			_curPortPool.gotError(e);

			_internalStack = 0;
			_last = null;
		}

		void requestEnsureConnection() {
			if (!_inRequest)
				return;

			if (_port != null)
				return;

			_port = _curPortPool.get();
		}

		void requestStart() {
			_inRequest = true;
			if (_port != null) {
				_port = null;
				System.err
						.println("ERROR.  somehow _port was not null at requestStart");
			}
		}

		void requestDone() {
			if (_port != null)
				_curPortPool.done(_port);
			_port = null;
			_inRequest = false;
			if (_internalStack > 0) {
				System.err
						.println("_internalStack in requestDone should be 0 is: "
								+ _internalStack);
				_internalStack = 0;
			}
		}

		void _reset() {
			_internalStack = 0;
			_port = null;
			_last = null;
		}

		int _internalStack = 0;

		DBPort _port;
		DBPort _last;
		boolean _inRequest;
	}

	void _pickInitial() throws MongoException {
		if (_curAddress != null)
			return;

		// we need to just get a server to query for ismaster
		_pickCurrent();

		try {
			_logger.info("current address beginning of _pickInitial: "
					+ _curAddress);

			DBObject im = isMasterCmd();
			if (_isMaster(im))
				return;

			synchronized (_allHosts) {
				Collections.shuffle(_allHosts);
				for (ServerAddress a : _allHosts) {
					if (_curAddress == a)
						continue;

					_logger.info("remote [" + _curAddress + "] -> [" + a + "]");
					_set(a);

					im = isMasterCmd();
					if (_isMaster(im))
						return;

					_logger.severe("switched to: " + a + " but isn't master");
				}

				throw new MongoException("can't find master");
			}
		} catch (Exception e) {
			_logger.log(Level.SEVERE,
					"can't pick initial master, using random one", e);
		}
	}

	private void _pickCurrent() throws MongoException {
		if (_allHosts == null)
			throw new MongoException(
					"got master/slave issue but not in master/slave mode on the client side");

		synchronized (_allHosts) {

			if (_curPortPool != null && _curPortPool.getRecentFailures() < DBPortPool.ALLOWED_ERRORS_BEFORE_CLEAR) {
				_logger.warning("Currently, we have "
						+ _curPortPool.getRecentFailures()
						+ " failures.  Keeping same MongoDB Server.");
				return;
			}

			Collections.shuffle(_allHosts);
			for (int i = 0; i < _allHosts.size(); i++) {
				ServerAddress a = _allHosts.get(i);
				if (a == _curAddress)
					continue;

				if (_curAddress != null) {
					_logger.info("switching from [" + _curAddress + "] to ["
							+ a + "]");
				}

				_set(a);
				return;
			}
		}

		throw new MongoException("couldn't find a new host to swtich too");
	}

	private boolean _set(ServerAddress addr) {
		if (_curAddress == addr)
			return false;
		_curAddress = addr;
		_curPortPool = _portHolder.get(addr.getSocketAddress());
		return true;
	}

	public String debugString() {
		StringBuilder buf = new StringBuilder("DBTCPConnector: ");
		if (_allHosts != null)
			buf.append("paired : ").append(_allHosts);
		else
			buf.append(_curAddress).append(" ").append(_curAddress._addr);

		return buf.toString();
	}

	DBObject isMasterCmd() {
		DBCollection collection = _mongo.getDB("admin").getCollection("$cmd");

		Iterator<DBObject> i = collection.__find(_isMaster, null, 0, 1, 0);
		if (i == null || !i.hasNext())
			throw new MongoException("no result for ismaster query?");

		DBObject res = i.next();
		if (i.hasNext())
			throw new MongoException("what's going on");

		return res;
	}

	boolean _isMaster(DBObject res) {
		Object x = res.get("ismaster");
		if (x == null)
			throw new IllegalStateException("ismaster shouldn't be null: "
					+ res);

		if (x instanceof Boolean)
			return (Boolean) x;

		if (x instanceof Number)
			return ((Number) x).intValue() == 1;

		throw new IllegalArgumentException("invalid ismaster [" + x + "] : "
				+ x.getClass().getName());
	}

	public void close() {
		_portHolder.close();
	}

	final Mongo _mongo;
	private ServerAddress _curAddress;
	private DBPortPool _curPortPool;
	private DBPortPool.Holder _portHolder;
	private final List<ServerAddress> _allHosts;

	private final ThreadLocal<MyPort> _threadPort = new ThreadLocal<MyPort>() {
		protected MyPort initialValue() {
			return new MyPort();
		}
	};

	private final static DBObject _isMaster = BasicDBObjectBuilder.start().add(
			"ismaster", 1).get();

}
