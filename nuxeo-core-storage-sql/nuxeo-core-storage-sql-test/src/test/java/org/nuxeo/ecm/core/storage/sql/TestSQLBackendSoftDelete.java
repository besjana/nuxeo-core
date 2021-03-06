/*
 * Copyright (c) 2013 Nuxeo SA (http://nuxeo.com/) and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Florent Guillaume
 */
package org.nuxeo.ecm.core.storage.sql;

import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeTrue;

import java.util.Calendar;

import org.junit.BeforeClass;
import org.junit.Test;

/**
 * All the tests of TestSQLBackend in soft-delete mode, plus additional tests.
 */
public class TestSQLBackendSoftDelete extends TestSQLBackend {

    /**
     * Only run for databases that support soft-delete.
     */
    @BeforeClass
    public static void assumeSoftDeleteSupported() {
        assumeTrue(DatabaseHelper.DATABASE.supportsSoftDelete());
    }

    @Override
    protected RepositoryDescriptor newDescriptor(long clusteringDelay,
            boolean fulltextDisabled) {
        RepositoryDescriptor descriptor = super.newDescriptor(clusteringDelay,
                fulltextDisabled);
        descriptor.softDeleteEnabled = true;
        return descriptor;
    }

    @Test
    public void testSoftDelete() throws Exception {
        Session session = repository.getConnection();
        Node root = session.getRootNode();
        Node folder1 = session.addChildNode(root, "folder1", null, "TestDoc",
                false);
        Node folder2 = session.addChildNode(root, "folder2", null, "TestDoc",
                false);
        Node folder3 = session.addChildNode(root, "folder3", null, "TestDoc",
                false);
        Node folder4 = session.addChildNode(folder1, "folder4", null,
                "TestDoc", false);
        session.addChildNode(folder4, "folder5", null, "TestDoc", false);
        // create node in folder1
        Node node = session.addChildNode(folder1, "node", null, "TestDoc",
                false);
        // create version
        Node ver = session.checkIn(node, "foolab1", "desc1");
        // create proxy2 in folder2
        session.addProxy(ver.getId(), node.getId(), folder2, "proxy2", null);
        // create proxy3 in folder3
        session.addProxy(ver.getId(), node.getId(), folder3, "proxy3", null);

        // remove folder2 and contained proxy
        session.removeNode(folder2);
        // remove version
        session.removeNode(ver);
        // remove folder4 and contained folder5
        session.removeNode(folder4);

        RepositoryManagement repoMgmt = RepositoryResolver.getRepository(repository.getName());
        assertEquals(5, repoMgmt.cleanupDeletedDocuments(0, null));
    }

    @Test
    public void testSoftDeleteCutoff() throws Exception {
        Session session = repository.getConnection();
        Node root = session.getRootNode();
        Node folder1 = session.addChildNode(root, "folder1", null, "TestDoc",
                false);
        Node node = session.addChildNode(folder1, "node", null, "TestDoc",
                false);

        // test date cutoff for cleanup
        session.removeNode(node);
        Thread.sleep(2000);
        Calendar cutoff = Calendar.getInstance();
        Thread.sleep(2000);
        session.removeNode(folder1);
        RepositoryManagement repoMgmt = RepositoryResolver.getRepository(repository.getName());
        assertEquals(1, repoMgmt.cleanupDeletedDocuments(0, cutoff));
        assertEquals(1, repoMgmt.cleanupDeletedDocuments(0, null));
    }

    @Test
    public void testSoftDeleteMax() throws Exception {
        Session session = repository.getConnection();
        Node root = session.getRootNode();
        Node folder = session.addChildNode(root, "folder", null, "TestDoc",
                false);
        for (int i = 0; i < 10; i++) {
            session.addChildNode(folder, "doc" + i, null, "TestDoc", false);
        }
        session.save();
        session.removeNode(folder);
        session.save();
        RepositoryManagement repoMgmt = RepositoryResolver.getRepository(repository.getName());
        assertEquals(1, repoMgmt.cleanupDeletedDocuments(1, null));
        assertEquals(3, repoMgmt.cleanupDeletedDocuments(3, null));
        assertEquals(7, repoMgmt.cleanupDeletedDocuments(0, null));
    }

}
