/***********************************************************************************************************************
 *
 * Mongo Tomcat Sessions
 * ==========================================
 *
 * Copyright (C) 2012 by Dawson Systems Ltd (http://www.dawsonsystems.com)
 *
 ***********************************************************************************************************************
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 **********************************************************************************************************************/

package com.dawsonsystems.session;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.TypeReference;
import com.mongodb.*;

import org.apache.catalina.*;
import org.apache.catalina.session.ManagerBase;
import org.apache.catalina.session.StandardSession;
import org.bson.types.ObjectId;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import java.util.logging.Level;
import java.util.logging.Logger;

public class MongoSessionManager extends ManagerBase implements Lifecycle {
	private static Logger log = Logger.getLogger("MongoManager");
	protected static String host = "localhost";
	protected static int port = 27017;
	protected static String database = "sessions";
	protected Mongo mongo;
	protected DB db;
	protected boolean slaveOk;

	public static void setLog(Logger log) {
		MongoSessionManager.log = log;
	}

	public static void setHost(String host) {
		MongoSessionManager.host = host;
	}

	public static void setPort(int port) {
		MongoSessionManager.port = port;
	}

	public static void setDatabase(String database) {
		MongoSessionManager.database = database;
	}

	private MongoSessionTrackerValve trackerValve;
	private ThreadLocal<StandardSession> currentSession = new ThreadLocal<StandardSession>();
	private Serializer serializer;
	// Either 'kryo' or 'java'
	private String serializationStrategyClass = "com.dawsonsystems.session.JavaSerializer";

	public Context getContext() {
		return super.getContext();
	}

	public void setContext(Context context) {
		super.setContext(context);
	}

	public void setMongo(Mongo mongo) {
		this.mongo = mongo;
	}

	public void setDb(DB db) {
		this.db = db;
	}

	public void setSlaveOk(boolean slaveOk) {
		this.slaveOk = slaveOk;
	}

	public void setTrackerValve(MongoSessionTrackerValve trackerValve) {
		this.trackerValve = trackerValve;
	}

	public void setCurrentSession(ThreadLocal<StandardSession> currentSession) {
		this.currentSession = currentSession;
	}

	public void setSerializer(Serializer serializer) {
		this.serializer = serializer;
	}

	public void setSerializationStrategyClass(String serializationStrategyClass) {
		this.serializationStrategyClass = serializationStrategyClass;
	}

	public String getInfo() {
		return "Mongo Session Manager";
	}

	public int getSessionIdLength() {
		return 24;
	}

	public void add(Session session) {
		try {
			save(session);
		} catch (IOException ex) {
			log.log(Level.SEVERE, "Error adding new session", ex);
		}
	}

	public void changeSessionId(Session session) {
		session.setId(new ObjectId().toString());
	}

	public Session createEmptySession() {
		MongoSession session = new MongoSession(this);
		session.setId(new ObjectId().toString());
		session.setMaxInactiveInterval(maxInactiveInterval);
		session.setValid(true);
		session.setCreationTime(System.currentTimeMillis());
		session.setNew(true);
		currentSession.set(session);
		log.fine("Created new empty session " + session.getIdInternal());
		return session;
	}

	public Session createSession(java.lang.String sessionId) {
		StandardSession session = (MongoSession) createEmptySession();

		log.fine("Created session with id " + session.getIdInternal() + " ( "
				+ sessionId + ")");
		if (sessionId != null) {
			session.setId(sessionId);
		}

		return session;
	}

	public Session findSession(String id) throws IOException {
		return loadSession(id);
	}

	public Session[] findSessions() {
		try {
			List<Session> sessions = new ArrayList<Session>();
			for (String sessionId : keys()) {
				sessions.add(loadSession(sessionId));
			}
			return sessions.toArray(new Session[sessions.size()]);
		} catch (IOException ex) {
			throw new RuntimeException(ex);
		}
	}

	public void expireSession(String sessionId) {
		Session s = null;
		try {
			s = this.findSession(sessionId);
		} catch (IOException e) {
			return;
		}
		if (s == null) {
			return;
		}
		s.expire();
	}

	public void remove(Session session) {
		log.fine("Removing session ID : " + session.getId());
		BasicDBObject query = new BasicDBObject();
		query.put("_id", session.getId());

		try {
			getCollection().remove(query);
		} catch (IOException e) {
			log.log(Level.SEVERE,
					"Error removing session in Mongo Session Store", e);
		} finally {
			currentSession.remove();
		}
	}

	public void backgroundProcess() {
		processExpires();
	}

	public void processExpires() {
		BasicDBObject query = new BasicDBObject();

		long olderThan = System.currentTimeMillis()
				- (getMaxInactiveInterval() * 1000);

		log.fine("Looking for sessions less than for expiry in Mongo : "
				+ olderThan);

		query.put("lastmodified", new BasicDBObject("$lt", olderThan));

		try {
			WriteResult result = getCollection().remove(query);
			log.fine("Expired sessions : " + result.getN());
		} catch (IOException e) {
			log.log(Level.SEVERE,
					"Error cleaning session in Mongo Session Store", e);
		}
	}

	protected void startInternal() throws LifecycleException {
		for (Valve valve : this.getContext().getPipeline().getValves()) {
			if (valve instanceof MongoSessionTrackerValve) {
				trackerValve = (MongoSessionTrackerValve) valve;
				trackerValve.setMongoManager(this);
				log.info("Attached to Mongo Tracker Valve");
				break;
			}
		}
		
		this.setState(LifecycleState.STARTING);
		
		try {
			initSerializer();
		} catch (ClassNotFoundException e) {
			log.log(Level.SEVERE, "Unable to load serializer", e);
			throw new LifecycleException(e);
		} catch (InstantiationException e) {
			log.log(Level.SEVERE, "Unable to load serializer", e);
			throw new LifecycleException(e);
		} catch (IllegalAccessException e) {
			log.log(Level.SEVERE, "Unable to load serializer", e);
			throw new LifecycleException(e);
		}
		log.info("Will expire sessions after " + getMaxInactiveInterval()
				+ " seconds");
		initDbConnection();
	}

	protected void stopInternal() throws LifecycleException {
		mongo.close();
	}

	public static String getDatabase() {
		return database;
	}

	public static String getHost() {
		return host;
	}

	public static int getPort() {
		return port;
	}

	public static Logger getLog() {
		return log;
	}

	public Mongo getMongo() {
		return mongo;
	}

	public DB getDb() {
		return db;
	}

	public boolean isSlaveOk() {
		return slaveOk;
	}

	public MongoSessionTrackerValve getTrackerValve() {
		return trackerValve;
	}

	public ThreadLocal<StandardSession> getCurrentSession() {
		return currentSession;
	}

	public Serializer getSerializer() {
		return serializer;
	}

	public String getSerializationStrategyClass() {
		return serializationStrategyClass;
	}

	@SuppressWarnings("deprecation")
	private void initDbConnection() throws LifecycleException {
		try {
			String[] hosts = getHost().split(",");

			List<ServerAddress> addrs = new ArrayList<ServerAddress>();

			for (String host : hosts) {
				addrs.add(new ServerAddress(host, getPort()));
			}
			mongo = new MongoClient(addrs);
			db = mongo.getDB(getDatabase());
			if (slaveOk) {
				db.slaveOk();
			}
			getCollection().ensureIndex(new BasicDBObject("lastmodified", 1));
			log.info("Connected to Mongo " + host + "/" + database
					+ " for session storage, slaveOk=" + slaveOk + ", "
					+ (getMaxInactiveInterval() * 1000) + " session live time");
		} catch (IOException e) {
			e.printStackTrace();
			throw new LifecycleException("Error Connecting to Mongo", e);
		}
	}

	private void initSerializer() throws ClassNotFoundException,
			IllegalAccessException, InstantiationException {
		log.info("Attempting to use serializer :" + serializationStrategyClass);
		serializer = (Serializer) Class.forName(serializationStrategyClass)
				.newInstance();
	}

	// =============================================
	// =============================================
	// =============================================
	public void save(Session session) throws IOException {
		try {
			log.fine("Saving session " + session + " into Mongo");

			StandardSession standardsession = (MongoSession) session;

			if (log.isLoggable(Level.FINE)) {
				log.fine("Session Contents [" + session.getId() + "]:");
				for (Object name : Collections.list(standardsession
						.getAttributeNames())) {
					log.fine("  " + name);
				}
			}

			Map<Object, Object> data = serializer
					.serializeFrom(standardsession);

			BasicDBObject dbsession = new BasicDBObject();
			dbsession.put("_id", standardsession.getId());
			dbsession.put("data", JSONObject.toJSONString(data));
			dbsession.put("lastmodified", System.currentTimeMillis());

			BasicDBObject query = new BasicDBObject();
			query.put("_id", standardsession.getIdInternal());
			getCollection().update(query, dbsession, true, false);
			log.fine("Updated session with id " + session.getIdInternal());
		} catch (IOException e) {
			log.severe(e.getMessage());
			e.printStackTrace();
			throw e;
		} finally {
			currentSession.remove();
			log.fine("Session removed from ThreadLocal :"
					+ session.getIdInternal());
		}
	}

	public Session loadSession(String id) throws IOException {

		if (id == null || id.length() == 0) {
			return createEmptySession();
		}

		StandardSession session = currentSession.get();

		if (session != null) {
			if (id.equals(session.getId())) {
				return session;
			} else {
				currentSession.remove();
			}
		}
		try {
			log.fine("Loading session " + id + " from Mongo");
			BasicDBObject query = new BasicDBObject();
			query.put("_id", id);

			DBObject dbsession = getCollection().findOne(query);

			if (dbsession == null) {
				log.fine("Session " + id + " not found in Mongo");
				StandardSession ret = getNewSession();
				ret.setId(id);
				currentSession.set(ret);
				return ret;
			}

			String map = dbsession.get("data").toString();
			Map<Object, Object> data = JSON.parseObject(map,
					new TypeReference<Map<Object, Object>>() {
					});
			;

			session = (MongoSession) createEmptySession();
			session.setId(id);
			session.setManager(this);
			serializer.deserializeInto(data, session);

			session.setMaxInactiveInterval(-1);
			session.access();
			session.setValid(true);
			session.setNew(false);

			if (log.isLoggable(Level.FINE)) {
				log.fine("Session Contents [" + session.getId() + "]:");
				for (Object name : Collections
						.list(session.getAttributeNames())) {
					log.fine("  " + name);
				}
			}

			log.fine("Loaded session id " + id);
			currentSession.set(session);
			return session;
		} catch (IOException e) {
			log.severe(e.getMessage());
			throw e;
		} catch (ClassNotFoundException ex) {
			log.log(Level.SEVERE, "Unable to deserialize session ", ex);
			throw new IOException("Unable to deserializeInto session", ex);
		}
	}

	public String[] keys() throws IOException {

		BasicDBObject restrict = new BasicDBObject();
		restrict.put("_id", 1);

		DBCursor cursor = getCollection().find(new BasicDBObject(), restrict);

		List<String> ret = new ArrayList<String>();

		while (cursor.hasNext()) {
			ret.add(cursor.next().get("").toString());
		}

		return ret.toArray(new String[ret.size()]);
	}

	private DBCollection getCollection() throws IOException {
		return db.getCollection("sessions");
	}

	public void load() throws ClassNotFoundException, IOException {
	}

	public void unload() throws IOException {
	}

}
