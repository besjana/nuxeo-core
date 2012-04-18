/*
 * Copyright (c) 2006-2011 Nuxeo SA (http://nuxeo.com/) and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     bstefanescu
 *     tdelprat
 *
 */

package org.nuxeo.ecm.core.io.impl.plugins;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import org.dom4j.Document;
import org.dom4j.DocumentException;
import org.dom4j.io.SAXReader;
import org.nuxeo.common.utils.FileUtils;
import org.nuxeo.common.utils.Path;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.impl.blob.StreamingBlob;
import org.nuxeo.ecm.core.io.ExportConstants;
import org.nuxeo.ecm.core.io.ExportedDocument;
import org.nuxeo.ecm.core.io.impl.AbstractDocumentReader;
import org.nuxeo.ecm.core.io.impl.DWord;
import org.nuxeo.ecm.core.io.impl.ExportedDocumentImpl;
import org.nuxeo.runtime.services.streaming.FileSource;
import org.nuxeo.runtime.services.streaming.ZipEntrySource;

/**
 * Reads nuxeo archives generated using {@link NuxeoArchiveWriter}.
 * <p>
 * If you need to read a CoreIO XML Archive that was not directly generated by
 * {@link NuxeoArchiveWriter} or that was modified you need to use the
 * NuxeoArchiveReader(File) constructor.
 * 
 * @author <a href="mailto:bs@nuxeo.com">Bogdan Stefanescu</a>
 */
public class NuxeoArchiveReader extends AbstractDocumentReader {

    private ZipInputStream in;

    private String file;

    private ZipFile zipFile;

    private List<String> zipIndex;

    private final Collection<File> filesToDelete = new ArrayList<File>();

    /**
     * Create a {@link NuxeoArchiveReader} from a url.
     * <p>
     * The url must point to an archive that was generated by
     * {@link NuxeoArchiveWriter}.
     * 
     * @param url url to an archive that was generated by NuxeoArchiveWriter
     * @throws IOException
     */
    public NuxeoArchiveReader(URL url) throws IOException {
        this(url.openStream());
        if (url.getProtocol().equals("file")) {
            file = FileUtils.getFileFromURL(url).getAbsolutePath();
        }
    }

    /**
     * Create a {@link NuxeoArchiveReader} from a {@link File}.
     * <p>
     * This constructor is different from others because it allows the input zip
     * file to have been generated by an other engine that
     * {@link NuxeoArchiveWriter}.
     * <p>
     * In particular, you can use this constructor on a Zip Archive that was
     * manually modified.
     * 
     * @param in InputStream pointing an archive that was generated by
     *            NuxeoArchiveWriter
     * @throws IOException
     */
    public NuxeoArchiveReader(File file) throws IOException {
        this.file = file.getAbsolutePath();
        this.zipFile = new ZipFile(file);
        buildOrderedZipIndex();
        checkMarker();
    }

    /**
     * Create a {@link NuxeoArchiveReader} from an {@link InputStream}.
     * <p>
     * The InputStream must point to an archive that was generated by
     * {@link NuxeoArchiveWriter}.
     * 
     * @param in InputStream pointing an archive that was generated by
     *            NuxeoArchiveWriter
     * @throws IOException
     */
    public NuxeoArchiveReader(InputStream in) throws IOException {
        this(new ZipInputStream(in));
    }

    /**
     * Create a {@link NuxeoArchiveReader} from an {@link ZipInputStream}.
     * <p>
     * The ZipInputStream must point to an archive that was generated by
     * {@link NuxeoArchiveWriter}.
     * 
     * @param in ZipInputStream pointing an archive that was generated by
     *            NuxeoArchiveWriter
     * @throws IOException
     */
    public NuxeoArchiveReader(ZipInputStream in) throws IOException {
        this(in, true);
    }

    /**
     * Package-visible constructor used by {@link ZipReader}.
     * 
     * @param in
     * @param checkMarker
     * @throws IOException
     */
    NuxeoArchiveReader(ZipInputStream in, boolean checkMarker)
            throws IOException {
        this.in = in;
        if (checkMarker) {
            checkMarker();
        }
    }

    protected void buildOrderedZipIndex() {
        zipIndex = new ArrayList<String>();
        Enumeration<? extends ZipEntry> entries = zipFile.entries();

        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            zipIndex.add(entry.getName());
        }
        Collections.sort(zipIndex, new Comparator<String>() {
            @Override
            public int compare(String spath1, String spath2) {
                return spath1.compareTo(spath2);
            }
        });
    }

    @Override
    public ExportedDocument read() throws IOException {
        if (zipFile != null) {
            return readZip();
        } else {
            return readOrderedStream();
        }
    }

    protected ExportedDocument readZip() throws IOException {

        if (zipIndex.size() == 0) {
            return null;
        }
        String idxname = zipIndex.remove(0);
        ZipEntry entry = zipFile.getEntry(idxname);
        if (entry == null) {
            return null;
        }

        if (!entry.isDirectory()) {
            if (entry.getName().equals(ExportConstants.MARKER_FILE)) {
                return read();
            } else if (entry.getName().equals(ExportConstants.DOCUMENT_FILE)) {
                // the repository ROOT! TODO: how to handle root? it doesn't
                // have a dir ..
                ExportedDocument xdoc = new ExportedDocumentImpl();
                xdoc.setPath(new Path("/"));
                xdoc.setDocument(loadXML(entry));
                return xdoc;
            } else {
                throw new IOException("Invalid Nuxeo archive on entry "
                        + entry.getName());
            }
        }

        // find the direct children entry that are part of the same document
        // since archive is modifiable we can not rely on the Extra bits thing
        List<String> childEntries = new ArrayList<String>();
        int depth = new Path(idxname).removeTrailingSeparator().segmentCount();
        for (String path : zipIndex) {
            if (path.startsWith(idxname)) {
                int subdepth = new Path(path).removeTrailingSeparator().segmentCount();
                if (subdepth != depth + 1
                        || zipFile.getEntry(path).isDirectory()) {
                    continue;
                }
                childEntries.add(path);
            } else {
                break;
            }
        }

        if (childEntries.size() == 0) {
            return read(); // empty dir -> try next directory
        }
        String name = entry.getName();
        ExportedDocument xdoc = new ExportedDocumentImpl();
        xdoc.setPath(new Path(name).removeTrailingSeparator());
        for (String childEntryName : childEntries) {
            int i = zipIndex.indexOf(childEntryName);
            idxname = zipIndex.remove(i);
            entry = zipFile.getEntry(idxname);
            name = entry.getName();
            if (name.endsWith(ExportConstants.DOCUMENT_FILE)) {
                xdoc.setDocument(loadXML(entry));
            } else if (name.endsWith(".xml")) { // external doc file
                xdoc.putDocument(FileUtils.getFileNameNoExt(entry.getName()),
                        loadXML(entry));
            } else { // should be a blob
                xdoc.putBlob(FileUtils.getFileName(entry.getName()),
                        createBlob(entry));
            }
        }
        return xdoc;
    }

    protected ExportedDocument readOrderedStream() throws IOException {
        ZipEntry entry = in.getNextEntry();
        if (entry == null) {
            return null;
        }
        if (!entry.isDirectory()) {
            if (entry.getName().equals(ExportConstants.MARKER_FILE)) {
                return read();
            } else if (entry.getName().equals(ExportConstants.DOCUMENT_FILE)) {
                // the repository ROOT! TODO: how to handle root? it doesn't
                // have a dir ..
                ExportedDocument xdoc = new ExportedDocumentImpl();
                xdoc.setPath(new Path("/"));
                xdoc.setDocument(loadXML(entry));
                return xdoc;
            } else {
                throw new IOException("Invalid Nuxeo archive");
            }
        }
        int count = getFilesCount(entry);
        if (count == 0) {
            return read(); // empty dir -> try next directory
        }
        String name = entry.getName();
        ExportedDocument xdoc = new ExportedDocumentImpl();
        xdoc.setPath(new Path(name).removeTrailingSeparator());
        for (int i = 0; i < count; i++) {
            entry = in.getNextEntry();
            name = entry.getName();
            if (name.endsWith(ExportConstants.DOCUMENT_FILE)) {
                xdoc.setDocument(loadXML(entry));
            } else if (name.endsWith(".xml")) { // external doc file
                xdoc.putDocument(FileUtils.getFileNameNoExt(entry.getName()),
                        loadXML(entry));
            } else { // should be a blob
                xdoc.putBlob(FileUtils.getFileName(entry.getName()),
                        createBlob(entry));
            }
        }
        return xdoc;
    }

    @Override
    public void close() {
        if (in != null) {
            try {
                in.close();
            } catch (IOException e) {
                // do nothing
            } finally {
                in = null;
            }
        }
        for (File file : filesToDelete) {
            file.delete();
        }
    }

    private static int getFilesCount(ZipEntry entry) throws IOException {
        byte[] bytes = entry.getExtra();
        if (bytes == null) {
            return 0;
        } else if (bytes.length != 4) {
            throw new IOException("Invalid Nuxeo Archive");
        } else {
            return new DWord(bytes).getInt();
        }
    }

    private Document loadXML(ZipEntry entry) throws IOException {
        try {
            // the SAXReader is closing the stream so that we need to copy the
            // content somewhere
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            if (zipFile != null) {
                FileUtils.copy(zipFile.getInputStream(entry), baos);
            } else {
                FileUtils.copy(in, baos);
            }
            return new SAXReader().read(new ByteArrayInputStream(
                    baos.toByteArray()));
        } catch (DocumentException e) {
            IOException ioe = new IOException("Failed to read zip entry "
                    + entry.getName() + ": " + e.getMessage());
            ioe.setStackTrace(e.getStackTrace());
            throw ioe;
        }
    }

    private Blob createBlob(ZipEntry entry) throws IOException {
        if (file != null) { // the zip is a file : optimize blob loading -> do
                            // not decompress blobs
            ZipEntrySource src = new ZipEntrySource(file, entry.getName());
            return new StreamingBlob(src);
        } else { // should decompress since this is a generic stream
            File file = File.createTempFile("nuxeo-import", "blob");
            filesToDelete.add(file);
            OutputStream out = new FileOutputStream(file);
            try {
                FileUtils.copy(in, out);
            } finally {
                out.close();
            }
            FileSource src = new FileSource(file);
            return new StreamingBlob(src);
        }
    }

    private void checkMarker() throws IOException {

        if (zipFile == null) {
            ZipEntry entry = in.getNextEntry();
            if (entry == null) {
                throw new IOException(
                        "Not a valid Nuxeo Archive - no marker file found (unexpected end of zip)");
            }
            if (!isMarkerEntry(entry)) {
                throw new IOException(
                        "Not a valid Nuxeo Archive - no marker file found");
            }
        } else {
            if (!zipIndex.contains(ExportConstants.MARKER_FILE)) {
                throw new IOException(
                        "Not a valid Nuxeo Archive - no marker file found");
            }
        }
    }

    public static boolean isMarkerEntry(ZipEntry entry) {
        return entry.getName().equals(ExportConstants.MARKER_FILE);
    }

}
