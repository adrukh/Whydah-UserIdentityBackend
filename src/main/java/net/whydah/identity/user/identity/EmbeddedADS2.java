package net.whydah.identity.user.identity;


import java.io.File;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import net.whydah.identity.util.FindFile;
import net.whydah.identity.util.StreamUtil;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.text.StrSubstitutor;
import org.apache.directory.api.ldap.model.entry.DefaultEntry;
import org.apache.directory.api.ldap.model.exception.LdapEntryAlreadyExistsException;
import org.apache.directory.api.ldap.model.ldif.LdifEntry;
import org.apache.directory.api.ldap.model.ldif.LdifReader;
import org.apache.directory.api.ldap.model.schema.SchemaManager;
import org.apache.directory.server.core.api.DirectoryService;
import org.apache.directory.server.core.api.partition.Partition;
import org.apache.directory.server.core.factory.DirectoryServiceFactory;
import org.apache.directory.server.core.factory.PartitionFactory;
import org.apache.directory.server.ldap.LdapServer;
import org.apache.directory.server.protocol.shared.transport.TcpTransport;
import org.apache.directory.server.protocol.shared.transport.Transport;
import org.jboss.logging.Logger;

/**
 * @author <a href="mailto:mposolda@redhat.com">Marek Posolda</a>
 */
public class EmbeddedADS2 {

    private static final Logger log = Logger.getLogger(EmbeddedADS2.class);

    public static final String PROPERTY_BASE_DN = "ldap.baseDN";
    public static final String PROPERTY_BIND_HOST = "ldap.host";
    public static final String PROPERTY_BIND_PORT = "ldap.port";
    public static final String PROPERTY_LDIF_FILE = "ldap.ldif";
    public static final String PROPERTY_SASL_PRINCIPAL = "ldap.saslPrincipal";
    public static final String PROPERTY_DSF = "ldap.dsf";

    private static final String DEFAULT_BASE_DN = "dc=keycloak,dc=org";
    private static final String DEFAULT_BIND_HOST = "localhost";
    private static final String DEFAULT_BIND_PORT = "10389";
    private static final String DEFAULT_LDIF_FILE = "classpath:ldap/default-users.ldif";

    public static final String DSF_INMEMORY = "mem";
    public static final String DSF_FILE = "file";
    public static final String DEFAULT_DSF = DSF_FILE;

    protected Properties defaultProperties;

    protected String baseDN;
    protected String bindHost;
    protected int bindPort;
    protected String ldifFile;
    protected String ldapSaslPrincipal;
    protected String directoryServiceFactory;

    protected DirectoryService directoryService;
    protected LdapServer ldapServer;


    public static void main(String[] args) throws Exception {
        Properties defaultProperties = new Properties();
        defaultProperties.put(PROPERTY_DSF, DSF_FILE);

        execute(args, defaultProperties);
    }

    public static void execute(String[] args, Properties defaultProperties) throws Exception {
        final EmbeddedADS2 ldapEmbeddedServer = new EmbeddedADS2(defaultProperties);
        ldapEmbeddedServer.init();
        ldapEmbeddedServer.start();

        Runtime.getRuntime().addShutdownHook(new Thread() {

            @Override
            public void run() {
                try {
                    ldapEmbeddedServer.stop();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

        });
    }

    public EmbeddedADS2(Properties defaultProperties) {
        this.defaultProperties = defaultProperties;

        this.baseDN = readProperty(PROPERTY_BASE_DN, DEFAULT_BASE_DN);
        this.bindHost = readProperty(PROPERTY_BIND_HOST, DEFAULT_BIND_HOST);
        String bindPort = readProperty(PROPERTY_BIND_PORT, DEFAULT_BIND_PORT);
        this.bindPort = Integer.parseInt(bindPort);
        this.ldifFile = readProperty(PROPERTY_LDIF_FILE, DEFAULT_LDIF_FILE);
        this.ldapSaslPrincipal = readProperty(PROPERTY_SASL_PRINCIPAL, null);
        this.directoryServiceFactory = readProperty(PROPERTY_DSF, DEFAULT_DSF);
    }

    protected String readProperty(String propertyName, String defaultValue) {
        String value = System.getProperty(propertyName);

        if (value == null || value.isEmpty()) {
            value = (String) this.defaultProperties.get(propertyName);
        }

        if (value == null || value.isEmpty()) {
            value = defaultValue;
        }

        return value;
    }


    public void init() throws Exception {
        log.info("Creating LDAP Directory Service. Config: baseDN=" + baseDN + ", bindHost=" + bindHost + ", bindPort=" + bindPort +
                ", ldapSaslPrincipal=" + ldapSaslPrincipal + ", directoryServiceFactory=" + directoryServiceFactory + ", ldif=" + ldifFile);

        this.directoryService = createDirectoryService();

        log.info("Importing LDIF: " + ldifFile);
        importLdif();

        log.info("Creating LDAP Server");
        this.ldapServer = createLdapServer();
    }


    public void start() throws Exception {
        log.info("Starting LDAP Server");
        ldapServer.start();
        log.info("LDAP Server started");
    }


    protected DirectoryService createDirectoryService() throws Exception {
        // Parse "keycloak" from "dc=keycloak,dc=org"
        String dcName = baseDN.split(",")[0];
        dcName = dcName.substring(dcName.indexOf("=") + 1);

        DirectoryServiceFactory dsf;
        if (this.directoryServiceFactory.equals(DSF_INMEMORY)) {
            dsf = new InMemoryDirectoryServiceFactory();
        } else if (this.directoryServiceFactory.equals(DSF_FILE)) {
            dsf = new FileDirectoryServiceFactory();
        } else {
            throw new IllegalStateException("Unknown value of directoryServiceFactory: " + this.directoryServiceFactory);
        }

        DirectoryService service = dsf.getDirectoryService();
        service.setAccessControlEnabled(false);
        service.setAllowAnonymousAccess(false);
        service.getChangeLog().setEnabled(false);

        dsf.init(dcName + "DS");

        SchemaManager schemaManager = service.getSchemaManager();

        PartitionFactory partitionFactory = dsf.getPartitionFactory();
        Partition partition = partitionFactory.createPartition(
                schemaManager,
                service.getDnFactory(),
                dcName,
                this.baseDN,
                1000,
                new File(service.getInstanceLayout().getPartitionsDirectory(), dcName));
        partition.setCacheService( service.getCacheService() );
        partition.initialize();

        partition.setSchemaManager( schemaManager );

        // Inject the partition into the DirectoryService
        service.addPartition( partition );

        // Last, process the context entry
        String entryLdif =
                "dn: " + baseDN + "\n" +
                        "dc: " + dcName + "\n" +
                        "objectClass: top\n" +
                        "objectClass: domain\n\n";
        importLdifContent(service, entryLdif);

        return service;
    }


    protected LdapServer createLdapServer() {
        LdapServer ldapServer = new LdapServer();

        ldapServer.setServiceName("DefaultLdapServer");
        ldapServer.setSearchBaseDn(this.baseDN);

        // Read the transports
        Transport ldap = new TcpTransport(this.bindHost, this.bindPort, 3, 50);
        ldapServer.addTransports( ldap );

        // Associate the DS to this LdapServer
        ldapServer.setDirectoryService( directoryService );

        // Propagate the anonymous flag to the DS
        directoryService.setAllowAnonymousAccess(false);

        return ldapServer;
    }


    private void importLdif() throws Exception {
        Map<String, String> map = new HashMap<String, String>();
        map.put("hostname", this.bindHost);
        if (this.ldapSaslPrincipal != null) {
            map.put("ldapSaslPrincipal", this.ldapSaslPrincipal);
        }

        // Find LDIF file on filesystem or classpath ( if it's like classpath:ldap/users.ldif )
        InputStream is = FindFile.findFile(ldifFile);
        if (is == null) {
            throw new IllegalStateException("LDIF file not found on classpath or on file system. Location was: " + ldifFile);
        }

        final String ldifContent = StrSubstitutor.replace(StreamUtil.readString(is), map);
        log.info("Content of LDIF: " + ldifContent);
        final SchemaManager schemaManager = directoryService.getSchemaManager();

        importLdifContent(directoryService, ldifContent);
    }

    private static void importLdifContent(DirectoryService directoryService, String ldifContent) throws Exception {
        LdifReader ldifReader = new LdifReader(IOUtils.toInputStream(ldifContent));

        try {
            for (LdifEntry ldifEntry : ldifReader) {
                try {
                    directoryService.getAdminSession().add(new DefaultEntry(directoryService.getSchemaManager(), ldifEntry.getEntry()));
                } catch (LdapEntryAlreadyExistsException ignore) {
                    log.info("Entry " + ldifEntry.getDn() + " already exists. Ignoring");
                }
            }
        } finally {
            ldifReader.close();
        }
    }


    public void stop() throws Exception {
        stopLdapServer();
        shutdownDirectoryService();
    }


    protected void stopLdapServer() {
        log.info("Stopping LDAP server.");
        ldapServer.stop();
    }


    protected void shutdownDirectoryService() throws Exception {
        log.info("Stopping Directory service.");
        directoryService.shutdown();

        // Delete workfiles just for 'inmemory' implementation used in tests. Normally we want LDAP data to persist
        File instanceDir = directoryService.getInstanceLayout().getInstanceDirectory();
        if (this.directoryServiceFactory.equals(DSF_INMEMORY)) {
            log.infof("Removing Directory service workfiles: %s", instanceDir.getAbsolutePath());
            FileUtils.deleteDirectory(instanceDir);
        } else {
            log.info("Working LDAP directory not deleted. Delete it manually if you want to start with fresh LDAP data. Directory location: " + instanceDir.getAbsolutePath());
        }
    }

}