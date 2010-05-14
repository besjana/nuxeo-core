/*
 * (C) Copyright 2010 Nuxeo SA (http://nuxeo.com/) and contributors.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser General Public License
 * (LGPL) version 2.1 which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/lgpl.html
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * Contributors:
 *     Florent Guillaume
 */
package org.nuxeo.ecm.core.storage.sql.net;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.io.Writer;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.nuxeo.ecm.core.NXCore;
import org.nuxeo.ecm.core.model.NoSuchRepositoryException;
import org.nuxeo.ecm.core.storage.StorageException;
import org.nuxeo.ecm.core.storage.sql.Mapper;
import org.nuxeo.ecm.core.storage.sql.Repository;
import org.nuxeo.ecm.core.storage.sql.RepositoryDescriptor;
import org.nuxeo.ecm.core.storage.sql.Row;
import org.nuxeo.ecm.core.storage.sql.Session;
import org.nuxeo.ecm.core.storage.sql.coremodel.SQLRepository;

/**
 * Servlet receiving remote {@link NetMapper} requests and sending them to an
 * actual mapper.
 */
public class NetServlet extends HttpServlet {

    private static final Log log = LogFactory.getLog(NetServlet.class);

    private static final long serialVersionUID = 1L;

    private String repositoryName;

    private Repository repository;

    public NetServlet(RepositoryDescriptor repositoryDescriptor) {
        repositoryName = repositoryDescriptor.name;
    }

    private boolean initialized;

    // currently connected sessions
    // TODO GC after timeout
    private Map<String, Session> sessions;

    protected synchronized void initialize() {
        if (initialized) {
            return;
        }
        initialized = true;
        org.nuxeo.ecm.core.model.Repository repo;
        try {
            try {
                repo = NXCore.getRepository(repositoryName);
            } catch (NoSuchRepositoryException e) {
                // No JDNI binding (embedded or unit tests)
                repo = NXCore.getRepositoryService().getRepositoryManager().getRepository(
                        repositoryName);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        if (repo instanceof Repository) {
            // (JCA) ConnectionFactoryImpl already implements Repository
            repository = (Repository) repo;
        } else if (repo instanceof SQLRepository) {
            // (LocalSession not pooled) SQLRepository
            // from SQLRepositoryFactory called by descriptor at registration
            repository = ((SQLRepository) repo).repository;
        } else {
            throw new RuntimeException("Unknown repository class: "
                    + repo.getClass().getName());
        }
        sessions = Collections.synchronizedMap(new HashMap<String, Session>());
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        initialize();
        String sid = req.getParameter("sid");
        if ("".equals(sid)) {
            sid = null;
        }
        InputStream is = req.getInputStream();
        Session session = null;
        try {
            // session
            if (sid == null) {
                session = repository.getConnection();
            } else {
                session = sessions.get(sid);
                if (session == null) {
                    throw new RuntimeException(
                            "Unknown session id (maybe timed out): " + sid);
                }
            }
            if (sid == null) {
                sid = session.getMapper().getMapperId();
                sessions.put(sid, session);
            }

            // set up output stream
            resp.setContentType("application/octet-stream");
            // resp.setCharacterEncoding("ISO-8859-1"); // important
            Writer writer = resp.getWriter();
            ObjectOutputStream oos = new ObjectOutputStream(
                    new OutputStreamToWriter(writer));

            // read method and args, and invoke it
            Object res = null;
            ObjectInputStream ois = new ObjectInputStream(is);
            while (true) {
                String methodName;
                try {
                    methodName = (String) ois.readObject();
                } catch (EOFException e) {
                    break;
                }
                List<Object> args = new LinkedList<Object>();
                try {
                    while (true) {
                        Object object = ois.readObject();
                        if (object == NetMapper.BARRIER) {
                            break;
                        }
                        args.add(object);
                    }
                } catch (EOFException e) {
                    // shouldn't happen, missing BARRIER
                    throw new RuntimeException("Unexpected EOF");
                }

                // invoke method, special case for close
                if ("close".equals(methodName)) {
                    session.close();
                    sessions.remove(sid);
                    res = null;
                } else {
                    res = invoke(session, methodName, args.toArray());
                }
            }

            // write last result
            oos.writeObject(res);
            oos.flush();
            oos.close();
        } catch (Exception e) {
            log.error(e);
            resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    e.toString());
        }
    }

    private static final Map<String, Method> mapperMethods = new HashMap<String, Method>();
    static {
        for (Method m : Mapper.class.getMethods()) {
            mapperMethods.put(m.getName(), m);
        }
    }

    protected Object invoke(Session session, String methodName, Object[] args)
            throws Exception {
        if ("readSingleRow".equals(methodName)) {
            String tableName = (String) args[0];
            Serializable id = (Serializable) args[1];
            return readSingleRow(session, tableName, id);
        }
        Method method = mapperMethods.get(methodName);
        if (method == null) {
            throw new StorageException("Unknown Mapper method: " + methodName);
        }
        try {
            return method.invoke(session.getMapper(), args);
        } catch (Exception e) {
            if (e instanceof InvocationTargetException) {
                throw new StorageException(e.getCause());
            }
            throw new StorageException(e);
        }
    }

    /*
     * ----- Methods that can check the cache before the database -----
     */

    public Row readSingleRow(Session session, String tableName, Serializable id)
            throws StorageException {
        // return session.readSingleRow(tableName, id);
        throw new UnsupportedOperationException();
    }

}
