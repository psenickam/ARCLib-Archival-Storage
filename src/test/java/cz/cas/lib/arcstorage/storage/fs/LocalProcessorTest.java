package cz.cas.lib.arcstorage.storage.fs;

import cz.cas.lib.arcstorage.domain.entity.Storage;
import cz.cas.lib.arcstorage.domain.entity.User;
import cz.cas.lib.arcstorage.dto.*;
import cz.cas.lib.arcstorage.storage.StorageServiceTest;
import cz.cas.lib.arcstorage.storage.exception.FileCorruptedAfterStoreException;
import cz.cas.lib.arcstorage.storage.exception.IOStorageException;
import lombok.Getter;
import org.apache.commons.io.FileUtils;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Arrays;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

import static cz.cas.lib.arcstorage.storage.StorageUtils.toXmlId;
import static helper.ThrowableAssertion.assertThrown;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

public class LocalProcessorTest extends StorageServiceTest {
    @Getter
    private LocalFsProcessor service = new LocalFsProcessor(storage, rootDirPath);
    private static Storage storage = new Storage();
    private static String rootDirPath;
    private static Properties props;
    private static String dataSpace;

    @BeforeClass
    public static void beforeClass() throws IOException {
        props = new Properties();
        props.load(ClassLoader.getSystemResourceAsStream("application.properties"));
        rootDirPath = props.getProperty("test.local.folderpath");
        dataSpace = props.getProperty("test.local.dataspace");
        Path testFolder = Paths.get(rootDirPath).resolve(dataSpace);
        FileUtils.deleteDirectory(testFolder.toFile());
        Files.createDirectories(testFolder);
        storage.setName("local storage");
    }

    @Override
    public String getDataSpace() {
        return dataSpace;
    }

    @Test
    @Override
    public void storeFileSuccessTest() throws Exception {
        String id = testName.getMethodName();
        Path path = getFolderPath(id).resolve(id);
        service.storeFile(getFolderPath(id), id, getSipStream(), SIP_CHECKSUM, new AtomicBoolean(false));
        assertThat(streamToString(new FileInputStream(path.toFile())), is(SIP_CONTENT));
        assertThat(getChecksumValue(path, SIP_CHECKSUM.getType()), is(SIP_CHECKSUM.getValue()));
        assertThat(isInState(path, ObjectState.ARCHIVED), is(true));
    }

    @Test
    @Override
    public void storeFileRollbackAware() throws Exception {
        String fileId = testName.getMethodName();

        File file = new File(LARGE_SIP_PATH);
        AtomicBoolean rollback = new AtomicBoolean(false);

        new Thread(() -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            rollback.set(true);
        }).start();

        LocalFsProcessor service = new TestServiceCatchingRollback(this.service.getStorage());
        Path path = getFolderPath(fileId).resolve(fileId);

        try (BufferedInputStream bos = new BufferedInputStream(new FileInputStream(file))) {
            service.storeFile(getFolderPath(fileId), fileId, bos, LARGE_SIP_CHECKSUM, rollback);
        }
        assertThat(isInState(path, ObjectState.PROCESSING), is(true));
    }

    @Test
    @Override
    public void storeFileSettingRollback() throws Exception {
        String fileId = testName.getMethodName();

        LocalFsProcessor service = new TestServiceSettingRollback(storage);

        AtomicBoolean rollback = new AtomicBoolean(false);

        assertThrown(() -> service.storeFile(getFolderPath(fileId), fileId, getSipStream(), SIP_CHECKSUM, rollback))
                .isInstanceOf(FileCorruptedAfterStoreException.class);
        assertThat(rollback.get(), is(true));

        rollback.set(false);

        assertThrown(() -> service.storeFile(getFolderPath(fileId), fileId, getSipStream(), null, rollback))
                .isInstanceOf(Throwable.class);
        assertThat(rollback.get(), is(true));
    }

    @Test
    @Override
    public void storeAipOk() throws Exception {
        String sipId = testName.getMethodName();
        String xmlId = toXmlId(sipId, 1);
        Path path = getFolderPath(sipId);
        AipDto aip = new AipDto("ownerId", sipId, getSipStream(), SIP_CHECKSUM, getXmlStream(), XML_CHECKSUM);
        AtomicBoolean rollback = new AtomicBoolean(false);
        service.storeAip(aip, rollback, dataSpace);

        assertThat(isInState(path.resolve(sipId), ObjectState.ARCHIVED), is(true));
        assertThat(streamToString(new FileInputStream(path.resolve(sipId).toFile())), is(SIP_CONTENT));
        assertThat(isInState(path.resolve(xmlId), ObjectState.ARCHIVED), is(true));
        assertThat(streamToString(new FileInputStream(path.resolve(xmlId).toFile())), is(XML_CONTENT));

        assertThat(rollback.get(), is(false));
    }

    @Test
    @Override
    public void storeXmlOk() throws Exception {
        String sipId = testName.getMethodName();
        AtomicBoolean rollback = new AtomicBoolean(false);
        String xmlId = toXmlId(sipId, 99);
        service.storeObject(new ArchivalObjectDto(xmlId, "databaseId", XML_CHECKSUM, new User("ownerId"), getXmlStream(), ObjectState.PROCESSING, Instant.now()), rollback, dataSpace);
        Path path = getFolderPath(xmlId);
        assertThat(isInState(path.resolve(xmlId), ObjectState.ARCHIVED), is(true));
        assertThat(streamToString(new FileInputStream(path.resolve(xmlId).toFile())), is(XML_CONTENT));
        assertThat(rollback.get(), is(false));
    }

    @Test
    @Override
    public void removeSipMultipleTimesOk() throws Exception {
        String sipId = testName.getMethodName();
        AipDto aip = new AipDto("ownerId", sipId, getSipStream(), SIP_CHECKSUM, getXmlStream(), XML_CHECKSUM);
        AtomicBoolean rollback = new AtomicBoolean(false);
        service.storeAip(aip, rollback, dataSpace);

        service.remove(sipId, dataSpace);
        service.remove(sipId, dataSpace);

        Path path = getFolderPath(sipId);
        assertThat(streamToString(new FileInputStream(path.resolve(sipId).toFile())), is(SIP_CONTENT));
        assertThat(isInState(path.resolve(sipId), ObjectState.REMOVED), is(true));
    }

    @Test
    @Override
    public void renewSipMultipleTimesOk() throws Exception {
        String sipId = testName.getMethodName();
        AipDto aip = new AipDto("ownerId", sipId, getSipStream(), SIP_CHECKSUM, getXmlStream(), XML_CHECKSUM);
        AtomicBoolean rollback = new AtomicBoolean(false);
        service.storeAip(aip, rollback, dataSpace);

        service.remove(sipId, dataSpace);
        service.renew(sipId, dataSpace);
        service.renew(sipId, dataSpace);

        Path path = getFolderPath(sipId);
        assertThat(streamToString(new FileInputStream(path.resolve(sipId).toFile())), is(SIP_CONTENT));
        assertThat(isInState(path.resolve(sipId), ObjectState.ARCHIVED), is(true));
    }

    @Test
    @Override
    public void deleteSipMultipleTimesOk() throws Exception {
        String sipId = testName.getMethodName();
        String xmlId = toXmlId(sipId, 1);
        AipDto aip = new AipDto("ownerId", sipId, getSipStream(), SIP_CHECKSUM, getXmlStream(), XML_CHECKSUM);
        AtomicBoolean rollback = new AtomicBoolean(false);
        service.storeAip(aip, rollback, dataSpace);

        service.delete(sipId, dataSpace);
        service.delete(sipId, dataSpace);

        Path path = getFolderPath(sipId);
        assertThat(Files.exists(path.resolve(sipId)), is(false));
        assertThat(isInState(path.resolve(sipId), ObjectState.DELETED), is(true));

        assertThat(streamToString(new FileInputStream(path.resolve(xmlId).toFile())), is(XML_CONTENT));
        assertThat(isInState(path.resolve(xmlId), ObjectState.ARCHIVED), is(true));
    }

    @Test
    @Override
    public void rollbackProcessingFile() throws Exception {
        String fileId = testName.getMethodName();

//preparation phase copied from rollbackAwareTest
        File file = new File(LARGE_SIP_PATH);
        AtomicBoolean rollback = new AtomicBoolean(false);

        new Thread(() -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            rollback.set(true);
        }).start();
//actual test
        LocalFsProcessor service = new TestServiceCatchingRollback(this.service.getStorage());
        Path path = getFolderPath(fileId);

        try (BufferedInputStream bos = new BufferedInputStream(new FileInputStream(file))) {
            service.storeFile(getFolderPath(fileId), fileId, bos, LARGE_SIP_CHECKSUM, rollback);
        }
        assertThat(isInState(path.resolve(fileId), ObjectState.PROCESSING), is(true));

        service.rollbackFile(path, fileId);

        assertThat(Files.exists(path.resolve(fileId)), is(false));
        assertThat(isInState(path.resolve(fileId), ObjectState.ROLLED_BACK), is(true));
    }

    @Test
    @Override
    public void rollbackStoredFileMultipleTimes() throws Exception {
        String fileId = testName.getMethodName();
        Path path = getFolderPath(fileId);

        service.storeFile(path, fileId, getSipStream(), SIP_CHECKSUM, new AtomicBoolean(false));
        service.rollbackFile(path, fileId);
        service.rollbackFile(path, fileId);

        assertThat(Files.exists(path.resolve(fileId)), is(false));
        assertThat(isInState(path.resolve(fileId), ObjectState.ROLLED_BACK), is(true));
    }

    @Test
    @Override
    public void rollbackCompletlyMissingFile() throws Exception {
        String fileId = testName.getMethodName();
        Path path = getFolderPath(fileId);

        service.rollbackFile(path, fileId);
        assertThat(isInState(path.resolve(fileId), ObjectState.ROLLED_BACK), is(true));
    }

    @Test
    @Override
    public void rollbackAipOk() throws Exception {
        String sipId = testName.getMethodName();
        String xmlId = toXmlId(sipId, 1);
        AipDto aip = new AipDto("ownerId", sipId, getSipStream(), SIP_CHECKSUM, getXmlStream(), XML_CHECKSUM);
        AtomicBoolean rollback = new AtomicBoolean(false);
        service.storeAip(aip, rollback, dataSpace);

        service.rollbackAip(sipId, dataSpace);

        Path path = getFolderPath(sipId);
        assertThat(Files.exists(path.resolve(sipId)), is(false));
        assertThat(Files.exists(path.resolve(xmlId)), is(false));

        assertThat(isInState(path.resolve(sipId), ObjectState.ROLLED_BACK), is(true));
        assertThat(isInState(path.resolve(xmlId), ObjectState.ROLLED_BACK), is(true));
    }

    @Test
    @Override
    public void rollbackXmlOk() throws Exception {
        String sipId = testName.getMethodName();
        String xmlId = toXmlId(sipId, 1);
        AipDto aip = new AipDto("ownerId", sipId, getSipStream(), SIP_CHECKSUM, getXmlStream(), XML_CHECKSUM);
        AtomicBoolean rollback = new AtomicBoolean(false);
        service.storeAip(aip, rollback, dataSpace);

        service.rollbackObject(toXmlId(sipId, 1), dataSpace);

        Path path = getFolderPath(sipId);

        assertThat(Files.exists(path.resolve(xmlId)), is(false));

        assertThat(streamToString(new FileInputStream(path.resolve(sipId).toFile())), is(SIP_CONTENT));
        assertThat(isInState(path.resolve(sipId), ObjectState.ARCHIVED), is(true));
        assertThat(isInState(path.resolve(xmlId), ObjectState.ROLLED_BACK), is(true));
    }

    @Test
    @Override
    public void testConnection() {
        Storage badConfig = new Storage();
        badConfig.setName("bad storage");
        LocalFsProcessor badService = new LocalFsProcessor(badConfig, "/blah");
        assertThat(service.testConnection(), is(true));
        assertThat(badService.testConnection(), is(false));
    }

    private String getChecksumValue(Path fileBasePath, ChecksumType checksumType) throws FileNotFoundException {
        return streamToString(new FileInputStream(fileBasePath.resolveSibling(fileBasePath.getFileName() + "." + checksumType).toFile()));
    }

    private boolean isInState(Path fileBasePath, ObjectState state) {
        if (!Files.exists(fileBasePath.resolveSibling(fileBasePath.getFileName() + "." + state)))
            return false;
        //when there is a rolled_back file, other files do not not have to be deleted
        if (state == ObjectState.ROLLED_BACK)
            return true;
        if (state == ObjectState.DELETED)
            return !(Files.exists(fileBasePath.resolveSibling(fileBasePath.getFileName() + "." + ObjectState.PROCESSING)) ||
                    Files.exists(fileBasePath.resolveSibling(fileBasePath.getFileName() + "." + ObjectState.ROLLED_BACK)));

        return Arrays.stream(ObjectState.values()).filter(o -> o != state).noneMatch(
                o -> Files.exists(fileBasePath.resolveSibling(fileBasePath.getFileName() + "." + o))
        );
    }

    private static final class TestServiceSettingRollback extends LocalFsProcessor {
        public TestServiceSettingRollback(Storage storage) {
            super(storage, props.getProperty("test.local.folderpath"));
        }

        @Override
        public Checksum computeChecksumRollbackAware(InputStream fileStream, ChecksumType checksumType, AtomicBoolean rollback) throws IOException {
            return new Checksum(ChecksumType.MD5, "alwayswrong");
        }
    }

    private Path getFolderPath(String fileName) {
        return service.getFolderPath(fileName, dataSpace);
    }

    private static final class TestServiceCatchingRollback extends LocalFsProcessor {
        public TestServiceCatchingRollback(Storage storage) {
            super(storage, props.getProperty("test.local.folderpath"));
        }

        @Override
        void storeFile(Path folder, String id, InputStream stream, Checksum checksum, AtomicBoolean rollback) throws FileCorruptedAfterStoreException, IOStorageException {
            if (rollback.get())
                return;
            try (BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(folder.resolve(id).toFile()))) {
                Files.createDirectories(folder);
                if (!Files.exists(folder.resolve(folder.resolve(id + ".PROCESSING"))))
                    Files.createFile(folder.resolve(id + ".PROCESSING"));
                Files.copy(new ByteArrayInputStream(checksum.getValue().getBytes()), folder.resolve(id + "." + checksum.getType()), StandardCopyOption.REPLACE_EXISTING);

                byte[] buffer = new byte[8192];
                int read = stream.read(buffer);
                while (read > 0) {
                    if (rollback.get())
                        return;
                    Thread.sleep(1000);
                    bos.write(buffer, 0, read);
                    read = stream.read(buffer);
                }
                bos.flush();
                boolean rollbackInterruption = !verifyChecksum(new FileInputStream(folder.resolve(id).toFile()), checksum, rollback);
                if (rollbackInterruption)
                    return;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}
