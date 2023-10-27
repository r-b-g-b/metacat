package edu.ucsb.nceas.metacat.index.queue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Calendar;
import java.util.Timer;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import org.apache.commons.io.IOUtils;
import org.dataone.service.types.v1.Identifier;
import org.dataone.service.types.v1.ObjectFormatIdentifier;
import org.dataone.service.types.v1.Session;
import org.dataone.service.types.v2.SystemMetadata;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import edu.ucsb.nceas.metacat.common.index.event.IndexEvent;
import edu.ucsb.nceas.metacat.dataone.D1NodeServiceTest;
import edu.ucsb.nceas.metacat.dataone.MNodeQueryTest;
import edu.ucsb.nceas.metacat.dataone.MNodeReplicationTest;
import edu.ucsb.nceas.metacat.dataone.MNodeService;
import edu.ucsb.nceas.metacat.index.IndexEventDAO;
import junit.framework.Test;
import junit.framework.TestSuite;

/**
 * The test class for the class of FailedIndexResubmitTimerTaskTest
 * @author tao
 *
 */
public class FailedIndexResubmitTimerTaskTest extends D1NodeServiceTest {
    private Session session = null;
    private Identifier guid = null;
    private String query = null;
    private String resultStr = null;
    
    /**
     * Get the test suite
     * @return the test suite
     */
    public static Test suite() {
        TestSuite suite = new TestSuite();
        suite.addTest(new FailedIndexResubmitTimerTaskTest("testCreateFailure"));
        suite.addTest(new FailedIndexResubmitTimerTaskTest("testDeleteFailure"));
        return suite;
    }
    
    /**
     * Overwrite the setUp method - insert an object and make sure indexing succeed.
     */
    public void setUp() throws Exception {
        //insert metadata
        session = getTestSession();
        guid = new Identifier();
        //guid.setValue("testCreateFailure.1698383743829");
        guid.setValue("testCreateFailure." + System.currentTimeMillis());
        InputStream object = 
                        new FileInputStream(new File(MNodeReplicationTest.replicationSourceFile));
        SystemMetadata sysmeta = createSystemMetadata(guid, session.getSubject(), object);
        object.close();
        ObjectFormatIdentifier formatId = new ObjectFormatIdentifier();
        formatId.setValue("eml://ecoinformatics.org/eml-2.0.1");
        sysmeta.setFormatId(formatId);
        object = new FileInputStream(new File(MNodeReplicationTest.replicationSourceFile));
        MNodeService.getInstance(request).create(session, guid, object, sysmeta);
        //Make sure the metadata objects have been indexed
        query = "q=id:" + guid.getValue();
        InputStream stream = MNodeService.getInstance(request).query(session, "solr", query);
        resultStr = IOUtils.toString(stream, "UTF-8");
        int count = 0;
        while ((resultStr == null || !resultStr.contains("checksum"))
                                                && count <= MNodeQueryTest.tryAcccounts) {
            Thread.sleep(1000);
            count++;
            stream = MNodeService.getInstance(request).query(session, "solr", query);
            resultStr = IOUtils.toString(stream, "UTF-8"); 
        }
    }
    
    /**
     * Constructor
     * @param name  the name of the test
     */
    public FailedIndexResubmitTimerTaskTest(String name) {
        super(name);
    }
    
    /**
     * Test the scenario that a create index task can't be put into the index queue
     * @throws Exception
     */
    public void testCreateFailure() throws Exception  {
        String originVersion = getSolrDocVersion(resultStr);
        //add the identifier to the index event as a create_failure index task
        IndexEvent event = new IndexEvent();
        event.setAction(IndexEvent.CREATE_FAILURE_TO_QUEUE);
        event.setDate(Calendar.getInstance().getTime());
        event.setDescription("Testing DAO");
        event.setIdentifier(guid);
        IndexEventDAO.getInstance().add(event);
        
        // check
        IndexEvent savedEvent = IndexEventDAO.getInstance().get(event.getIdentifier());
        assertEquals(event.getIdentifier(), savedEvent.getIdentifier());
        assertEquals(event.getAction(), savedEvent.getAction());
        assertEquals(event.getDate(), savedEvent.getDate());
        assertEquals(event.getDescription(), savedEvent.getDescription());
        
        // create timer to resubmit the failed index task
        Timer indexTimer = new Timer();
        long delay = 0;
        indexTimer.schedule(new FailedIndexResubmitTimerTask(), delay);
        
        // check if a reindex happened (the solr doc version changed)
        boolean versionChanged = false;
        InputStream stream = MNodeService.getInstance(request).query(session, "solr", query);
        resultStr = IOUtils.toString(stream, "UTF-8");
        int count = 0;
        String newVersion = null;
        while (!versionChanged && count <= MNodeQueryTest.tryAcccounts) {
            Thread.sleep(1000);
            count++;
            stream = MNodeService.getInstance(request).query(session, "solr", query);
            resultStr = IOUtils.toString(stream, "UTF-8");
            newVersion = getSolrDocVersion(resultStr);
            versionChanged = !newVersion.equals(originVersion);
        }
        assertTrue(versionChanged);
        
        // the saved event should be deleted
        savedEvent = IndexEventDAO.getInstance().get(event.getIdentifier());
        assertNull(savedEvent);
    }
    
    /**
     * Test the scenario that a delete index task can't be put into the index queue
     * @throws Exception
     */
    public void testDeleteFailure() throws Exception  {
        //add the identifier to the index event as a create_failure index task
        IndexEvent event = new IndexEvent();
        event.setAction(IndexEvent.DELETE_FAILURE_TO_QUEUE);
        event.setDate(Calendar.getInstance().getTime());
        event.setDescription("Testing DAO");
        event.setIdentifier(guid);
        IndexEventDAO.getInstance().add(event);
        
        // check
        IndexEvent savedEvent = IndexEventDAO.getInstance().get(event.getIdentifier());
        assertEquals(event.getIdentifier(), savedEvent.getIdentifier());
        assertEquals(event.getAction(), savedEvent.getAction());
        assertEquals(event.getDate(), savedEvent.getDate());
        assertEquals(event.getDescription(), savedEvent.getDescription());
        
        // create timer to resubmit the failed index task
        Timer indexTimer = new Timer();
        long delay = 0;
        indexTimer.schedule(new FailedIndexResubmitTimerTask(), delay);
        
        // wait until the the solr doc is deleted
        InputStream stream = MNodeService.getInstance(request).query(session, "solr", query);
        resultStr = IOUtils.toString(stream, "UTF-8");
        int count = 0;
        while ((resultStr != null && resultStr.contains("checksum"))
                                                && count <= MNodeQueryTest.tryAcccounts) {
            Thread.sleep(1000);
            count++;
            stream = MNodeService.getInstance(request).query(session, "solr", query);
            resultStr = IOUtils.toString(stream, "UTF-8"); 
        }
        assertTrue(!resultStr.contains("checksum"));
        
        // the saved event should be deleted
        savedEvent = IndexEventDAO.getInstance().get(event.getIdentifier());
        assertNull(savedEvent);
    }
    
    /**
     * Parse the solr doc to get the version number string
     * @param xml  the solr doc in the xml format
     * @return the version string in the solr doc
     * @throws ParserConfigurationException
     * @throws SAXException
     * @throws IOException
     * @throws XPathExpressionException
     */
    private String getSolrDocVersion(String xml) throws ParserConfigurationException, SAXException,
                                                    IOException, XPathExpressionException {
        DocumentBuilderFactory builderFactory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = builderFactory.newDocumentBuilder();
        Document xmlDocument = builder
                               .parse(new InputSource(new ByteArrayInputStream(xml.getBytes())));
        XPath xPath = XPathFactory.newInstance().newXPath();
        String expression = "//long[@name='_version_']";
        NodeList nodeList = (NodeList) xPath.compile(expression)
                                    .evaluate(xmlDocument, XPathConstants.NODESET);
        Node node = nodeList.item(0);
        String version = node.getFirstChild().getNodeValue();
        return version;
    }

}
