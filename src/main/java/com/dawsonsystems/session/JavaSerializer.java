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

import javax.servlet.http.HttpSession;
import java.io.*;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class JavaSerializer implements Serializer {

	public Map<Object, Object> serializeFrom(HttpSession session)
			throws IOException {
		Map<Object, Object> map = new HashMap<Object, Object>();
		Enumeration<String> names = session.getAttributeNames();
		while (names.hasMoreElements()) {
			String key = names.nextElement();
			Object value = session.getAttribute(key);
			map.put(key, value);
		}
		return map;
	}

	public HttpSession deserializeInto(Map<Object, Object> data,
			HttpSession session) throws IOException, ClassNotFoundException {
		MongoSession standardSession = (MongoSession) session;
		Set<Object> keys = data.keySet();
        for(Object key:keys){
        	standardSession.setAttribute((String) key, data.get(key));
        }
		return session;
	}
}
