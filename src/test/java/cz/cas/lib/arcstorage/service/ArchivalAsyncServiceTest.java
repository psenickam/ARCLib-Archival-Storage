package cz.cas.lib.arcstorage.service;

import cz.cas.lib.arcstorage.domain.entity.*;
import cz.cas.lib.arcstorage.dto.ObjectState;
import cz.cas.lib.arcstorage.mail.ArcstorageMailCenter;
import cz.cas.lib.arcstorage.storage.fs.LocalFsProcessor;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;

import static cz.cas.lib.arcstorage.storage.StorageUtils.toXmlId;
import static cz.cas.lib.arcstorage.util.Utils.asList;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.Assert.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.*;

public class ArchivalAsyncServiceTest {
    private static final ArchivalAsyncService service = new ArchivalAsyncService();
    @Mock
    private ArchivalDbService archivalDbService;
    @Mock
    private ArcstorageMailCenter mailCenter;
    @Mock
    private LocalFsProcessor localFsProcessor;
    private static final User USER = new User(UUID.randomUUID().toString(), null, null, "SPACE", null, null);


    @Before
    public void setup() throws Exception {
        MockitoAnnotations.initMocks(this);
        service.setArchivalDbService(archivalDbService);
        service.setMailCenter(mailCenter);
        service.setExecutor(Executors.newFixedThreadPool(1));
        Storage s = new Storage();
        s.setName("name");
        when(localFsProcessor.getStorage()).thenReturn(s);
    }


    @Test
    public void cleanUp() throws Exception {
        ArchivalObject o1 = new ArchivalObject(null, USER, ObjectState.ARCHIVAL_FAILURE);
        ArchivalObject o2 = new ArchivalObject(null, USER, ObjectState.DELETION_FAILURE);
        AipSip s1 = new AipSip(UUID.randomUUID().toString(), null, USER, ObjectState.PROCESSING);
        AipXml x1 = new AipXml(UUID.randomUUID().toString(), null, USER, s1, 1, ObjectState.DELETION_FAILURE);
        AipXml x2 = new AipXml(UUID.randomUUID().toString(), null, USER, s1, 2, ObjectState.PRE_PROCESSING);
        List<ArchivalObject> objects = asList(o1, o2, s1, x1, x2);

        doThrow(Exception.class).when(localFsProcessor).rollbackObject(toXmlId(s1.getId(), 2), USER.getDataSpace());
        doThrow(Exception.class).when(localFsProcessor).delete(o2.getId(), USER.getDataSpace());

        service.cleanUp(objects, asList(localFsProcessor));

        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(archivalDbService).setObjectsState(eq(ObjectState.DELETED), captor.capture());
        assertThat((List<ArchivalObject>) captor.getValue(), containsInAnyOrder(x1));

        captor = ArgumentCaptor.forClass(List.class);
        verify(archivalDbService).setObjectsState(eq(ObjectState.ROLLED_BACK), captor.capture());
        assertThat((List<ArchivalObject>) captor.getValue(), containsInAnyOrder(o1, s1));

        verify(archivalDbService, times(2)).setObjectsState(any(), any());
    }
}