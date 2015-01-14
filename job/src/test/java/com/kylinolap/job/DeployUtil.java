package com.kylinolap.job;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;

import org.apache.commons.io.IOUtils;
import org.apache.hadoop.hbase.util.Pair;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.tools.ant.filters.StringInputStream;
import org.codehaus.plexus.util.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.kylinolap.common.KylinConfig;
import com.kylinolap.common.persistence.ResourceStore;
import com.kylinolap.common.persistence.ResourceTool;
import com.kylinolap.common.util.AbstractKylinTestCase;
import com.kylinolap.common.util.CliCommandExecutor;
import com.kylinolap.common.util.HiveClient;
import com.kylinolap.cube.CubeInstance;
import com.kylinolap.cube.CubeManager;
import com.kylinolap.job.dataGen.FactTableGenerator;
import com.kylinolap.job.engine.JobEngineConfig;
import com.kylinolap.job.hadoop.hive.SqlHiveDataTypeMapping;
import com.kylinolap.job.tools.LZOSupportnessChecker;
import com.kylinolap.metadata.MetadataManager;
import com.kylinolap.metadata.model.ColumnDesc;
import com.kylinolap.metadata.model.TableDesc;

public class DeployUtil {
    @SuppressWarnings("unused")
    private static final Logger logger = LoggerFactory.getLogger(DeployUtil.class);

    public static void initCliWorkDir() throws IOException {
        execCliCommand("rm -rf " + getHadoopCliWorkingDir());
        execCliCommand("mkdir -p " + config().getKylinJobLogDir());
    }

    public static void deployMetadata() throws IOException {
        // install metadata to hbase
        ResourceTool.reset(config());
        ResourceTool.copy(KylinConfig.createInstanceFromUri(AbstractKylinTestCase.LOCALMETA_TEST_DATA), config());

        // update cube desc signature.
        for (CubeInstance cube : CubeManager.getInstance(config()).listAllCubes()) {
            cube.getDescriptor().setSignature(cube.getDescriptor().calculateSignature());
            CubeManager.getInstance(config()).updateCube(cube);
        }
    }

    public static void overrideJobJarLocations() {
        Pair<File, File> files = getJobJarFiles();
        File jobJar = files.getFirst();
        File coprocessorJar = files.getSecond();

        config().overrideKylinJobJarPath(jobJar.getAbsolutePath());
        config().overrideCoprocessorLocalJar(coprocessorJar.getAbsolutePath());
    }

    public static void deployJobJars() throws IOException {
        Pair<File, File> files = getJobJarFiles();
        File jobJar = files.getFirst();
        File coprocessorJar = files.getSecond();

        File jobJarRemote = new File(config().getKylinJobJarPath());
        File jobJarLocal = new File(jobJar.getParentFile(), jobJarRemote.getName());
        if (jobJar.equals(jobJarLocal) == false) {
            FileUtils.copyFile(jobJar, jobJarLocal);
        }

        File coprocessorJarRemote = new File(config().getCoprocessorLocalJar());
        File coprocessorJarLocal = new File(coprocessorJar.getParentFile(), coprocessorJarRemote.getName());
        if (coprocessorJar.equals(coprocessorJarLocal) == false) {
            FileUtils.copyFile(coprocessorJar, coprocessorJarLocal);
        }

        CliCommandExecutor cmdExec = config().getCliCommandExecutor();
        cmdExec.copyFile(jobJarLocal.getAbsolutePath(), jobJarRemote.getParent());
        cmdExec.copyFile(coprocessorJar.getAbsolutePath(), coprocessorJarRemote.getParent());
    }

    private static Pair<File, File> getJobJarFiles() {
        String version;
        try {
            MavenXpp3Reader pomReader = new MavenXpp3Reader();
            Model model = pomReader.read(new FileReader("../pom.xml"));
            version = model.getVersion();
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }

        File jobJar = new File("../job/target", "kylin-job-" + version + "-job.jar");
        File coprocessorJar = new File("../storage/target", "kylin-storage-" + version + "-coprocessor.jar");
        return new Pair<File, File>(jobJar, coprocessorJar);
    }

    public static void overrideJobConf(String confDir) throws IOException {
        boolean enableLzo = LZOSupportnessChecker.getSupportness();
        overrideJobConf(confDir, enableLzo);
    }

    public static void overrideJobConf(String confDir, boolean enableLzo) throws IOException {
        File src = new File(confDir, JobEngineConfig.HADOOP_JOB_CONF_FILENAME + (enableLzo ? ".lzo_enabled" : ".lzo_disabled") + ".xml");
        File dst = new File(confDir, JobEngineConfig.HADOOP_JOB_CONF_FILENAME + ".xml");
        FileUtils.copyFile(src, dst);
    }

    private static void execCliCommand(String cmd) throws IOException {
        config().getCliCommandExecutor().execute(cmd);
    }

    private static String getHadoopCliWorkingDir() {
        return config().getCliWorkingDir();
    }

    private static KylinConfig config() {
        return KylinConfig.getInstanceFromEnv();
    }

    // ============================================================================

    static final String TABLE_CAL_DT = "edw.test_cal_dt";
    static final String TABLE_CATEGORY_GROUPINGS = "default.test_category_groupings";
    static final String TABLE_KYLIN_FACT = "default.test_kylin_fact";
    static final String TABLE_SELLER_TYPE_DIM = "edw.test_seller_type_dim";
    static final String TABLE_SITES = "edw.test_sites";

    static final String[] TABLE_NAMES = new String[] { TABLE_CAL_DT, TABLE_CATEGORY_GROUPINGS, TABLE_KYLIN_FACT, TABLE_SELLER_TYPE_DIM, TABLE_SITES };

    public static void prepareTestData(String joinType, String cubeName) throws Exception {

        String factTableName = TABLE_KYLIN_FACT.toUpperCase();
        String content = null;

        boolean buildCubeUsingProvidedData = Boolean.parseBoolean(System.getProperty("buildCubeUsingProvidedData"));
        if (!buildCubeUsingProvidedData) {
            System.out.println("build cube with random dataset");
            // data is generated according to cube descriptor and saved in resource store
            if (joinType.equalsIgnoreCase("inner")) {
                content = FactTableGenerator.generate(cubeName, "10000", "1", null, "inner");
            } else if (joinType.equalsIgnoreCase("left")) {
                content = FactTableGenerator.generate(cubeName, "10000", "0.6", null, "left");
            } else {
                throw new IllegalArgumentException("Unsupported join type : " + joinType);
            }

            assert content != null;
            overrideFactTableData(content, factTableName);
        } else {
            System.out.println("build cube with provided dataset");
        }

        duplicateFactTableData(factTableName, joinType);
        deployHiveTables();
    }

    public static void overrideFactTableData(String factTableContent, String factTableName) throws IOException {
        // Write to resource store
        ResourceStore store = ResourceStore.getStore(config());

        InputStream in = new StringInputStream(factTableContent);
        String factTablePath = "/data/" + factTableName + ".csv";
        store.deleteResource(factTablePath);
        store.putResource(factTablePath, in, System.currentTimeMillis());
        in.close();
    }

    public static void duplicateFactTableData(String factTableName, String joinType) throws IOException {
        // duplicate a copy of this fact table, with a naming convention with fact.csv.inner or fact.csv.left
        // so that later test cases can select different data files
        ResourceStore store = ResourceStore.getStore(config());
        InputStream in = store.getResource("/data/" + factTableName + ".csv");
        String factTablePathWithJoinType = "/data/" + factTableName + ".csv." + joinType.toLowerCase();
        store.deleteResource(factTablePathWithJoinType);
        store.putResource(factTablePathWithJoinType, in, System.currentTimeMillis());
        in.close();
    }

    private static void deployHiveTables() throws Exception {

        MetadataManager metaMgr = MetadataManager.getInstance(config());

        // scp data files, use the data from hbase, instead of local files
        File temp = File.createTempFile("temp", ".csv");
        temp.createNewFile();
        for (String tablename : TABLE_NAMES) {
            tablename = tablename.toUpperCase();

            File localBufferFile = new File(temp.getParent() + "/" + tablename + ".csv");
            localBufferFile.createNewFile();

            InputStream hbaseDataStream = metaMgr.getStore().getResource("/data/" + tablename + ".csv");
            FileOutputStream localFileStream = new FileOutputStream(localBufferFile);
            IOUtils.copy(hbaseDataStream, localFileStream);

            hbaseDataStream.close();
            localFileStream.close();

            localBufferFile.deleteOnExit();
        }
        String tableFileDir = temp.getParent();
        temp.delete();

        HiveClient hiveClient = new HiveClient();
        
        // create hive tables
        hiveClient.executeHQL("CREATE DATABASE IF NOT EXISTS EDW");
        hiveClient.executeHQL(generateCreateTableHql(metaMgr.getTableDesc(TABLE_CAL_DT.toUpperCase())));
        hiveClient.executeHQL(generateCreateTableHql(metaMgr.getTableDesc(TABLE_CATEGORY_GROUPINGS.toUpperCase())));
        hiveClient.executeHQL(generateCreateTableHql(metaMgr.getTableDesc(TABLE_KYLIN_FACT.toUpperCase())));
        hiveClient.executeHQL(generateCreateTableHql(metaMgr.getTableDesc(TABLE_SELLER_TYPE_DIM.toUpperCase())));
        hiveClient.executeHQL(generateCreateTableHql(metaMgr.getTableDesc(TABLE_SITES.toUpperCase())));

        // load data to hive tables
        // LOAD DATA LOCAL INPATH 'filepath' [OVERWRITE] INTO TABLE tablename
        hiveClient.executeHQL(generateLoadDataHql(TABLE_CAL_DT, tableFileDir));
        hiveClient.executeHQL(generateLoadDataHql(TABLE_CATEGORY_GROUPINGS, tableFileDir));
        hiveClient.executeHQL(generateLoadDataHql(TABLE_KYLIN_FACT, tableFileDir));
        hiveClient.executeHQL(generateLoadDataHql(TABLE_SELLER_TYPE_DIM, tableFileDir));
        hiveClient.executeHQL(generateLoadDataHql(TABLE_SITES, tableFileDir));
    }

    private static String generateLoadDataHql(String tableName, String tableFileDir) {
        return "LOAD DATA LOCAL INPATH '" + tableFileDir + "/" + tableName.toUpperCase() + ".csv' OVERWRITE INTO TABLE " + tableName.toUpperCase();
    }

    private static String[] generateCreateTableHql(TableDesc tableDesc) {
        
        String dropsql = "DROP TABLE IF EXISTS " + tableDesc.getIdentity();
        StringBuilder ddl = new StringBuilder();

        ddl.append("CREATE TABLE " + tableDesc.getIdentity() + "\n");
        ddl.append("(" + "\n");

        for (int i = 0; i < tableDesc.getColumns().length; i++) {
            ColumnDesc col = tableDesc.getColumns()[i];
            if (i > 0) {
                ddl.append(",");
            }
            ddl.append(col.getName() + " " + SqlHiveDataTypeMapping.getHiveDataType((col.getDatatype())) + "\n");
        }

        ddl.append(")" + "\n");
        ddl.append("ROW FORMAT DELIMITED FIELDS TERMINATED BY ','" + "\n");
        ddl.append("STORED AS TEXTFILE");

        return new String[] {dropsql, ddl.toString()};
    }

}
