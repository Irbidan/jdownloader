//    jDownloader - Downloadmanager
//    Copyright (C) 2009  JD-Team support@jdownloader.org
//
//    This program is free software: you can redistribute it and/or modify
//    it under the terms of the GNU General Public License as published by
//    the Free Software Foundation, either version 3 of the License, or
//    (at your option) any later version.
//
//    This program is distributed in the hope that it will be useful,
//    but WITHOUT ANY WARRANTY; without even the implied warranty of
//    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
//    GNU General Public License for more details.
//
//    You should have received a copy of the GNU General Public License
//    along with this program.  If not, see <http://www.gnu.org/licenses/>.

package jd.controlling.downloadcontroller;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;

import jd.config.NoOldJDDataBaseFoundException;
import jd.controlling.packagecontroller.AbstractNode;
import jd.controlling.packagecontroller.AbstractPackageChildrenNodeFilter;
import jd.controlling.packagecontroller.PackageController;
import jd.parser.Regex;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLinkProperty;
import jd.plugins.FilePackage;
import jd.plugins.FilePackageProperty;
import jd.plugins.PluginForHost;
import jd.utils.JDUtilities;

import org.appwork.controlling.SingleReachableState;
import org.appwork.exceptions.WTFException;
import org.appwork.scheduler.DelayedRunnable;
import org.appwork.shutdown.ShutdownController;
import org.appwork.shutdown.ShutdownEvent;
import org.appwork.shutdown.ShutdownRequest;
import org.appwork.storage.JSonStorage;
import org.appwork.storage.TypeRef;
import org.appwork.storage.config.JsonConfig;
import org.appwork.utils.Application;
import org.appwork.utils.IO;
import org.appwork.utils.StringUtils;
import org.appwork.utils.event.Eventsender;
import org.appwork.utils.event.queue.Queue;
import org.appwork.utils.event.queue.Queue.QueuePriority;
import org.appwork.utils.event.queue.QueueAction;
import org.appwork.utils.io.J7FileList;
import org.appwork.utils.logging2.LogSource;
import org.appwork.utils.os.CrossSystem;
import org.appwork.utils.zip.ZipIOReader;
import org.appwork.utils.zip.ZipIOWriter;
import org.jdownloader.afterdownload.AccountLoader;
import org.jdownloader.controlling.DownloadLinkAggregator;
import org.jdownloader.controlling.DownloadLinkWalker;
import org.jdownloader.controlling.FileCreationManager;
import org.jdownloader.controlling.download.DownloadControllerEvent;
import org.jdownloader.controlling.download.DownloadControllerEventAddedPackage;
import org.jdownloader.controlling.download.DownloadControllerEventDataUpdate;
import org.jdownloader.controlling.download.DownloadControllerEventRemovedLinkList;
import org.jdownloader.controlling.download.DownloadControllerEventRemovedPackage;
import org.jdownloader.controlling.download.DownloadControllerEventSender;
import org.jdownloader.controlling.download.DownloadControllerEventStructureRefresh;
import org.jdownloader.controlling.download.DownloadControllerListener;
import org.jdownloader.controlling.lists.DupeManager;
import org.jdownloader.gui.views.components.packagetable.LinkTreeUtils;
import org.jdownloader.logging.LogController;
import org.jdownloader.plugins.FinalLinkState;
import org.jdownloader.plugins.controller.host.PluginFinder;
import org.jdownloader.settings.CleanAfterDownloadAction;
import org.jdownloader.settings.GeneralSettings;
import org.jdownloader.settings.GeneralSettings.CreateFolderTrigger;
import org.jdownloader.settings.staticreferences.CFG_GENERAL;

public class DownloadController extends PackageController<FilePackage, DownloadLink> {

    private transient DownloadControllerEventSender eventSender         = new DownloadControllerEventSender();

    private final DelayedRunnable                   downloadSaver;
    private final DelayedRunnable                   changesSaver;
    private final CopyOnWriteArrayList<File>        downloadLists       = new CopyOnWriteArrayList<File>();

    public static final ScheduledExecutorService    TIMINGQUEUE         = DelayedRunnable.getNewScheduledExecutorService();

    private final DupeManager                       dupeController;

    public static SingleReachableState              DOWNLOADLIST_LOADED = new SingleReachableState("DOWNLOADLIST_COMPLETE");

    private static DownloadController               INSTANCE            = new DownloadController();

    private static Object                           SAVELOADLOCK        = new Object();

    /**
     * darf erst nachdem der JDController init wurde, aufgerufen werden
     */
    public static DownloadController getInstance() {
        return INSTANCE;
    }

    @Override
    public void moveOrAddAt(final FilePackage pkg, final List<DownloadLink> movechildren, final int moveChildrenindex, final int pkgIndex) {
        getQueue().add(new QueueAction<Void, RuntimeException>() {

            @Override
            protected Void run() throws RuntimeException {
                final HashMap<FilePackage, List<DownloadLink>> sourceMap = new HashMap<FilePackage, List<DownloadLink>>();
                for (final DownloadLink dl : movechildren) {
                    List<DownloadLink> list = sourceMap.get(dl.getParentNode());
                    if (list == null) {
                        list = new ArrayList<DownloadLink>();
                        sourceMap.put(dl.getParentNode(), list);
                    }
                    list.add(dl);
                }
                DownloadController.super.moveOrAddAt(pkg, movechildren, moveChildrenindex, pkgIndex);
                for (final Entry<FilePackage, List<DownloadLink>> s : sourceMap.entrySet()) {
                    DownloadWatchDog.getInstance().handleMovedDownloadLinks(pkg, s.getKey(), s.getValue());
                }
                return null;
            }
        });
    }

    private DownloadController() {

        dupeController = new DupeManager();
        ShutdownController.getInstance().addShutdownEvent(new ShutdownEvent() {

            @Override
            public long getMaxDuration() {
                return 0;
            }

            @Override
            public void onShutdown(final ShutdownRequest shutdownRequest) {
                final boolean idle = DownloadWatchDog.getInstance().isIdle();
                saveDownloadLinks();
                if (!idle) {
                    int retry = 10;
                    while (retry > 0) {
                        if (DownloadWatchDog.getInstance().isIdle()) {
                            /*
                             * we wait till the DownloadWatchDog is finished or max 10 secs
                             */
                            break;
                        }
                        try {
                            Thread.sleep(1000);
                        } catch (final InterruptedException e) {
                            break;
                        }
                        retry--;
                    }
                    saveDownloadLinks();
                }
            }

            @Override
            public String toString() {
                return "ShutdownEvent: Save Downloadlist";
            }
        });
        changesSaver = new DelayedRunnable(TIMINGQUEUE, 5000l, 60000l) {

            @Override
            public void run() {
                if (allowSaving()) {
                    super.run();
                }
            }

            @Override
            public String getID() {
                return "DownloadController:Save_Changes";
            }

            @Override
            public void delayedrun() {
                saveDownloadLinks();
            }
        };
        downloadSaver = new DelayedRunnable(TIMINGQUEUE, 60 * 1000l, 5 * 60 * 1000l) {
            @Override
            public void run() {
                if (allowSaving()) {
                    super.run();
                }
            }

            @Override
            public String getID() {
                return "DownloadController:Save_Download";
            }

            @Override
            public void delayedrun() {
                saveDownloadLinks();
            }

        };
        this.eventSender.addListener(new DownloadControllerListener() {

            @Override
            public void onDownloadControllerAddedPackage(FilePackage pkg) {
                changesSaver.run();
            }

            @Override
            public void onDownloadControllerStructureRefresh(FilePackage pkg) {
                changesSaver.run();
            }

            @Override
            public void onDownloadControllerStructureRefresh() {
                changesSaver.run();
            }

            @Override
            public void onDownloadControllerStructureRefresh(AbstractNode node, Object param) {
                changesSaver.run();
            }

            @Override
            public void onDownloadControllerRemovedPackage(FilePackage pkg) {
                changesSaver.run();
            }

            @Override
            public void onDownloadControllerRemovedLinklist(List<DownloadLink> list) {
                changesSaver.run();
            }

            @Override
            public void onDownloadControllerUpdatedData(DownloadLink downloadlink, DownloadLinkProperty property) {
                changesSaver.run();
            }

            @Override
            public void onDownloadControllerUpdatedData(FilePackage pkg, FilePackageProperty property) {
                changesSaver.run();
            }

            @Override
            public void onDownloadControllerUpdatedData(DownloadLink downloadlink) {
                changesSaver.run();
            }

            @Override
            public void onDownloadControllerUpdatedData(FilePackage pkg) {
                changesSaver.run();
            }
        });

        new AccountLoader().init();
    }

    public void requestSaving() {
        downloadSaver.run();
    }

    @Override
    protected void _controllerPackageNodeAdded(FilePackage pkg, QueuePriority priority) {
        dupeController.invalidate();
        eventSender.fireEvent(new DownloadControllerEventStructureRefresh());
        eventSender.fireEvent(new DownloadControllerEventAddedPackage(pkg));
    }

    public Eventsender<DownloadControllerListener, DownloadControllerEvent> getEventSender() {
        return eventSender;
    }

    @Override
    protected void _controllerPackageNodeRemoved(FilePackage pkg, QueuePriority priority) {

        eventSender.fireEvent(new DownloadControllerEventRemovedPackage(pkg));
    }

    @Override
    protected void _controllerParentlessLinks(final List<DownloadLink> links, QueuePriority priority) {
        dupeController.invalidate();
        eventSender.fireEvent(new DownloadControllerEventRemovedLinkList(new ArrayList<DownloadLink>(links)));
    }

    @Override
    public void removePackage(FilePackage pkg) {
        super.removePackage(pkg);
    }

    @Override
    public void removeChildren(List<DownloadLink> removechildren) {
        super.removeChildren(removechildren);
    }

    @Override
    public void removeChildren(FilePackage pkg, List<DownloadLink> children, boolean doNotifyParentlessLinks) {
        super.removeChildren(pkg, children, doNotifyParentlessLinks);
    }

    @Override
    protected void _controllerStructureChanged(QueuePriority priority) {

        eventSender.fireEvent(new DownloadControllerEventStructureRefresh());
    }

    /**
     * add all given FilePackages to this DownloadController at the beginning
     *
     * @param fps
     */
    public void addAll(final java.util.List<FilePackage> fps) {
        addAllAt(fps, 0);
    }

    /**
     * add/move all given FilePackages at given Position
     *
     * @param fp
     * @param index
     * @param repos
     * @return
     */
    public void addAllAt(final java.util.List<FilePackage> fps, final int index) {
        if (fps != null && fps.size() > 0) {
            QUEUE.add(new QueueAction<Void, RuntimeException>() {

                @Override
                protected Void run() throws RuntimeException {
                    int counter = index;
                    boolean createFolder = CFG_GENERAL.CREATE_FOLDER_TRIGGER.getValue() == CreateFolderTrigger.ON_LINKS_ADDED;
                    HashSet<String> created = new HashSet<String>();
                    for (FilePackage fp : fps) {
                        if (createFolder) {
                            String folder = fp.getDownloadDirectory();
                            if (created.add(folder)) {
                                File folderFile = new File(folder);
                                if (folderFile.exists()) {
                                    /* folder already exists */
                                    logger.info("Skip folder creation: " + folderFile + " already exists");
                                } else {
                                    /* folder does not exist */

                                    try {
                                        DownloadWatchDog.getInstance().validateDestination(folderFile);
                                        if (folderFile.mkdirs()) {
                                            logger.info("Create folder: " + folderFile);
                                        } else {
                                            logger.info("Could not create folder: " + folderFile);
                                        }
                                    } catch (BadDestinationException e) {
                                        logger.info("Not allowed to create folder: " + e.getFile());
                                    }

                                }
                            }
                        }
                        addmovePackageAt(fp, counter++);
                    }
                    return null;
                }
            });
        }
    }

    public void addListener(final DownloadControllerListener l) {
        eventSender.addListener(l);
    }

    public void addListener(final DownloadControllerListener l, boolean weak) {
        eventSender.addListener(l, weak);
    }

    public ArrayList<FilePackage> getPackages() {
        return packages;
    }

    /**
     * checks if this DownloadController contains a DownloadLink with given url
     *
     * @param url
     * @return
     */
    public boolean hasDownloadLinkwithURL(final String url) {
        if (url != null) {
            final String correctUrl = url.trim();
            for (final FilePackage fp : getPackagesCopy()) {
                boolean readL2 = fp.getModifyLock().readLock();
                try {
                    for (DownloadLink dl : fp.getChildren()) {
                        if (correctUrl.equalsIgnoreCase(dl.getPluginPatternMatcher())) {
                            return true;
                        }
                    }
                } finally {
                    fp.getModifyLock().readUnlock(readL2);
                }
            }
        }
        return false;
    }

    private ArrayList<File> findAvailableDownloadLists() {
        logger.info("Collect Lists");
        File[] filesInCfg = null;
        final File cfg = Application.getResource("cfg/");
        if (Application.getJavaVersion() >= Application.JAVA17) {
            try {
                filesInCfg = J7FileList.findFiles(Pattern.compile("^downloadList.*?\\.zip$", Pattern.CASE_INSENSITIVE), cfg, true).toArray(new File[0]);
            } catch (IOException e) {
                logger.log(e);
            }
        }
        if (filesInCfg == null) {
            filesInCfg = Application.getResource("cfg/").listFiles();
        }
        ArrayList<Long> sortedAvailable = new ArrayList<Long>();
        ArrayList<File> ret = new ArrayList<File>();
        if (filesInCfg != null) {
            for (File downloadList : filesInCfg) {
                final String name = downloadList.getName();
                if (name.startsWith("downloadList") && downloadList.isFile()) {
                    String counter = new Regex(name, "downloadList(\\d+)\\.zip$").getMatch(0);
                    if (counter != null) {
                        sortedAvailable.add(Long.parseLong(counter));
                    }
                }
            }
            Collections.sort(sortedAvailable, Collections.reverseOrder());
        }
        for (Long loadOrder : sortedAvailable) {
            ret.add(Application.getResource("cfg/downloadList" + loadOrder + ".zip"));
        }
        if (Application.getResource("cfg/downloadList.zip").exists()) {
            ret.add(Application.getResource("cfg/downloadList.zip"));
        }
        logger.info("Lists: " + ret);
        downloadLists.addAll(ret);
        return ret;
    }

    /**
     * load all FilePackages/DownloadLinks from Database
     */
    public void initDownloadLinks() {
        QUEUE.add(new QueueAction<Void, RuntimeException>(Queue.QueuePriority.HIGH) {

            @Override
            protected Void run() throws RuntimeException {
                logger.info("Init DownloadList");
                LinkedList<FilePackage> lpackages = null;
                File loadedList = null;
                for (File downloadList : findAvailableDownloadLists()) {
                    try {
                        lpackages = load(downloadList);
                        if (lpackages != null) {
                            loadedList = downloadList;
                            break;
                        }
                    } catch (final Throwable e) {
                        logger.log(e);
                    }
                }
                try {
                    if (lpackages == null) {
                        logger.info("Try to import old LinkList");
                        lpackages = loadDownloadLinks();
                    }
                } catch (final Throwable e) {
                    logger.log(e);
                }
                if (lpackages != null) {
                    int links = 0;
                    for (FilePackage fp : lpackages) {
                        links += fp.size();
                    }
                    logger.info("LinkList found: " + lpackages.size() + "/" + links);
                } else {
                    logger.info("LinkList empty!");
                    lpackages = new LinkedList<FilePackage>();
                }
                try {
                    preProcessFilePackages(lpackages, true);
                    for (final FilePackage filePackage : lpackages) {
                        filePackage.setControlledBy(DownloadController.this);
                    }
                    writeLock();
                    try {
                        packages.addAll(0, lpackages);
                    } finally {
                        writeUnlock();
                    }
                    updateUniqueAlltimeIDMaps(lpackages);
                    dupeController.invalidate();
                    final long version = backendChanged.incrementAndGet();
                    childrenChanged.set(version);
                    structureChanged.set(version);
                    eventSender.fireEvent(new DownloadControllerEventStructureRefresh());
                } catch (final Throwable e) {
                    if (loadedList != null) {
                        final File renameTo = new File(loadedList.getAbsolutePath() + ".backup");
                        boolean backup = false;
                        try {
                            if (loadedList.exists()) {
                                if (loadedList.renameTo(renameTo) == false) {
                                    IO.copyFile(loadedList, renameTo);
                                }
                                backup = true;
                            }
                        } catch (final Throwable e2) {
                            logger.log(e2);
                        }
                        logger.severe("Could backup " + loadedList + " to " + renameTo + " ->" + backup);
                    }
                    logger.log(e);
                } finally {
                    DOWNLOADLIST_LOADED.setReached();

                }
                return null;
            }

        });
    }

    private class LoadedPackage {
        private FilePackage                          filePackage   = null;
        private final HashMap<Integer, DownloadLink> downloadLinks = new HashMap<Integer, DownloadLink>();

        private FilePackage getLoadedPackage() {
            if (filePackage != null) {
                if (filePackage.getChildren().size() == 0) {
                    final List<Integer> childIndices = new ArrayList<Integer>(downloadLinks.keySet());
                    Collections.sort(childIndices);
                    for (final Integer childIndex : childIndices) {
                        final DownloadLink child = downloadLinks.get(childIndex);
                        filePackage.getChildren().add(child);
                        child.setParentNode(filePackage);
                    }
                }
                return filePackage;
            }
            return null;
        }
    }

    private LinkedList<FilePackage> load(File file) {
        logger.info("Load List: " + file);
        synchronized (SAVELOADLOCK) {
            LinkedList<FilePackage> ret = null;
            if (file != null && file.exists()) {
                ZipIOReader zip = null;
                try {
                    zip = new ZipIOReader(file);
                    /* lets restore the FilePackages from Json */
                    final HashMap<Integer, LoadedPackage> packageMap = new HashMap<Integer, LoadedPackage>();
                    DownloadControllerStorable dcs = null;
                    InputStream is = null;
                    final ByteArrayOutputStream byteBuffer = new ByteArrayOutputStream(32767) {
                        @Override
                        public synchronized byte[] toByteArray() {
                            /* avoid creating new byteArray */
                            return buf;
                        }
                    };
                    for (ZipEntry entry : zip.getZipFiles()) {
                        byteBuffer.reset();
                        String json = null;
                        try {
                            if (entry.getName().matches("^\\d+_\\d+$")) {
                                final String idx[] = entry.getName().split("_");
                                final Integer packageIndex = Integer.valueOf(idx[0]);
                                final Integer childIndex = Integer.valueOf(idx[1]);
                                LoadedPackage loadedPackage = packageMap.get(packageIndex);
                                if (loadedPackage == null) {
                                    loadedPackage = new LoadedPackage();
                                    packageMap.put(packageIndex, loadedPackage);
                                }
                                is = zip.getInputStream(entry);
                                final byte[] bytes = IO.readStream((int) entry.getSize(), is, byteBuffer, true);
                                json = new String(bytes, 0, byteBuffer.size(), "UTF-8");
                                final DownloadLinkStorable storable = JSonStorage.restoreFromString(json, new TypeRef<DownloadLinkStorable>() {
                                }, null);
                                if (storable != null) {
                                    loadedPackage.downloadLinks.put(childIndex, storable._getDownloadLink());
                                } else {
                                    throw new WTFException("restored a null DownloadLinkLinkStorable");
                                }
                            } else if (entry.getName().matches("^\\d+$")) {
                                final Integer packageIndex = Integer.valueOf(entry.getName());
                                is = zip.getInputStream(entry);
                                final byte[] bytes = IO.readStream((int) entry.getSize(), is, byteBuffer, true);
                                json = new String(bytes, 0, byteBuffer.size(), "UTF-8");
                                final FilePackageStorable storable = JSonStorage.stringToObject(json, new TypeRef<FilePackageStorable>() {
                                }, null);
                                if (storable != null) {
                                    LoadedPackage loadedPackage = packageMap.get(packageIndex);
                                    if (loadedPackage == null) {
                                        loadedPackage = new LoadedPackage();
                                        packageMap.put(packageIndex, loadedPackage);
                                    }
                                    loadedPackage.filePackage = storable._getFilePackage();
                                } else {
                                    throw new WTFException("restored a null FilePackageStorable");
                                }
                            } else if ("extraInfo".equalsIgnoreCase(entry.getName())) {
                                is = zip.getInputStream(entry);
                                final byte[] bytes = IO.readStream((int) entry.getSize(), is, byteBuffer, true);
                                json = new String(bytes, 0, byteBuffer.size(), "UTF-8");
                                dcs = JSonStorage.stringToObject(json, new TypeRef<DownloadControllerStorable>() {
                                }, null);
                            }
                        } catch (Throwable e) {
                            logger.log(e);
                            logger.info("String was: " + json);
                            if (entry != null) {
                                logger.info("Entry:" + entry + "|Size:" + entry.getSize() + "|Compressed Size:" + entry.getCompressedSize());
                            } else {
                                logger.info("Entry: " + entry);
                            }
                            throw e;
                        } finally {
                            try {
                                is.close();
                            } catch (final Throwable e) {
                            }
                        }
                    }
                    /* sort positions */
                    final List<Integer> packageIndices = new ArrayList<Integer>(packageMap.keySet());
                    Collections.sort(packageIndices);
                    /* build final ArrayList of CrawledPackage */
                    final List<FilePackage> ret2 = new ArrayList<FilePackage>(packageIndices.size());
                    for (final Integer packageIndex : packageIndices) {
                        final LoadedPackage loadedPackage = packageMap.get(packageIndex);
                        final FilePackage filePackage = loadedPackage.getLoadedPackage();
                        if (filePackage != null) {
                            ret2.add(filePackage);
                        } else {
                            throw new WTFException("FilePackage at Index " + packageIndex + " is missing!");
                        }
                    }
                    if (dcs != null && JsonConfig.create(GeneralSettings.class).isConvertRelativePathsJDRoot()) {
                        try {
                            final String oldRootPath = dcs.getRootPath();
                            if (!StringUtils.isEmpty(oldRootPath)) {
                                final String newRoot = JDUtilities.getJDHomeDirectoryFromEnvironment().getAbsolutePath();
                                /*
                                 * convert paths relative to JDownloader root,only in jared version
                                 */
                                for (FilePackage pkg : ret2) {
                                    if (!CrossSystem.isAbsolutePath(pkg.getDownloadDirectory())) {
                                        /* no need to convert relative paths */
                                        continue;
                                    }
                                    final String pkgPath = LinkTreeUtils.getDownloadDirectory(pkg).getAbsolutePath();
                                    if (pkgPath.startsWith(oldRootPath + "/")) {
                                        /*
                                         * folder is inside JDRoot, lets update it
                                         */
                                        final String restPath = pkgPath.substring(oldRootPath.length());
                                        final String newPath = new File(newRoot, restPath).getAbsolutePath();
                                        pkg.setDownloadDirectory(newPath);
                                    }
                                }
                            }
                        } catch (final Throwable e) {
                            /* this method can throw exceptions, eg in SVN */
                            logger.log(e);
                        }
                    }
                    ret = new LinkedList<FilePackage>(ret2);
                } catch (final Throwable e) {
                    try {
                        zip.close();
                    } catch (final Throwable e2) {
                    }
                    final File renameTo = new File(file.getAbsolutePath() + ".backup");
                    boolean backup = false;
                    try {
                        if (file.exists()) {
                            if (file.renameTo(renameTo) == false) {
                                IO.copyFile(file, renameTo);
                            }
                            backup = true;
                        }
                    } catch (final Throwable e2) {
                    }
                    logger.severe("Could backup " + file + " to " + renameTo + " ->" + backup);
                    logger.log(e);
                } finally {
                    try {
                        zip.close();
                    } catch (final Throwable e) {
                    }
                }
            }
            return ret;
        }
    }

    /**
     * load FilePackages and DownloadLinks from database
     *
     * @return
     * @throws Exception
     */
    private LinkedList<FilePackage> loadDownloadLinks() throws Exception {
        Object obj = null;
        try {
            obj = JDUtilities.getDatabaseConnector().getLinks();
        } catch (final NoOldJDDataBaseFoundException e) {
            return null;
        }
        if (obj != null && obj instanceof ArrayList && (((java.util.List<?>) obj).size() == 0 || ((java.util.List<?>) obj).size() > 0 && ((java.util.List<?>) obj).get(0) instanceof FilePackage)) {
            final LinkedList<FilePackage> ret = new LinkedList<FilePackage>((java.util.List<FilePackage>) obj);
            if (ret != null) {
                for (final FilePackage fp : ret) {
                    for (final DownloadLink link : fp.getChildren()) {
                        link.setParentNode(fp);
                    }
                }
            }
            return ret;
        }
        throw new Exception("Linklist incompatible");
    }

    public void processFinalLinkState(DownloadLink localLink) {
        FinalLinkState currentFinalLinkState = localLink.getFinalLinkState();
        if (currentFinalLinkState != null) {
            if (currentFinalLinkState == FinalLinkState.PLUGIN_DEFECT) {
                localLink.setFinalLinkState(null);
            }
            return;
        }
    }

    public void checkPluginUpdates() {
        if (DOWNLOADLIST_LOADED.isReached()) {
            DownloadWatchDog.getInstance().enqueueJob(new DownloadWatchDogJob() {

                @Override
                public void execute(DownloadSession currentSession) {
                    QUEUE.addWait(new QueueAction<Void, RuntimeException>() {
                        private final PluginFinder finder = new PluginFinder(logger);

                        @Override
                        protected Void run() throws RuntimeException {
                            getChildrenByFilter(new AbstractPackageChildrenNodeFilter<DownloadLink>() {

                                @Override
                                public int returnMaxResults() {
                                    return 0;
                                }

                                private final void updatePluginInstance(DownloadLink link) {
                                    final long currentDefaultVersion;
                                    final String currentDefaultHost;
                                    final PluginForHost defaultPlugin = link.getDefaultPlugin();
                                    if (defaultPlugin != null) {
                                        currentDefaultHost = defaultPlugin.getLazyP().getHost();
                                        currentDefaultVersion = defaultPlugin.getLazyP().getVersion();
                                    } else {
                                        currentDefaultHost = null;
                                        currentDefaultVersion = -1;
                                    }
                                    final PluginForHost newDefaultPlugin = finder.assignPlugin(link, true);
                                    final long newDefaultVersion;
                                    final String newDefaultHost;
                                    if (newDefaultPlugin != null) {
                                        newDefaultVersion = newDefaultPlugin.getLazyP().getVersion();
                                        newDefaultHost = newDefaultPlugin.getLazyP().getHost();
                                    } else {
                                        newDefaultVersion = -1;
                                        newDefaultHost = null;
                                    }
                                    if (newDefaultPlugin != null && (currentDefaultVersion != newDefaultVersion || !StringUtils.equals(currentDefaultHost, newDefaultHost))) {
                                        logger.info("Update Plugin for: " + link.getName() + ":" + link.getHost() + " to " + newDefaultPlugin.getLazyP().getDisplayName() + ":" + newDefaultPlugin.getLazyP().getVersion());
                                        link.setDefaultPlugin(newDefaultPlugin);
                                        if (link.getFinalLinkState() == FinalLinkState.PLUGIN_DEFECT) {
                                            link.setFinalLinkState(null);
                                        }
                                    }
                                }

                                @Override
                                public boolean acceptNode(final DownloadLink node) {
                                    final SingleDownloadController controller = node.getDownloadLinkController();
                                    if (controller != null) {
                                        controller.getJobsAfterDetach().add(new DownloadWatchDogJob() {

                                            @Override
                                            public void execute(DownloadSession currentSession) {
                                                updatePluginInstance(node);
                                            }

                                            @Override
                                            public void interrupt() {
                                            }
                                        });
                                    } else {
                                        updatePluginInstance(node);
                                    }
                                    return false;
                                }
                            });
                            return null;
                        }
                    });
                }

                @Override
                public void interrupt() {
                }
            });
        }
    }

    public void preProcessFilePackages(LinkedList<FilePackage> fps, boolean allowCleanup) {
        if (fps == null || fps.size() == 0) {
            return;
        }
        final Iterator<FilePackage> iterator = fps.iterator();
        DownloadLink localLink;
        Iterator<DownloadLink> it;
        FilePackage fp;
        PluginFinder pluginFinder = new PluginFinder(logger);
        boolean cleanupStartup = allowCleanup && CleanAfterDownloadAction.CLEANUP_ONCE_AT_STARTUP.equals(org.jdownloader.settings.staticreferences.CFG_GENERAL.CFG.getCleanupAfterDownloadAction());
        boolean cleanupFileExists = JsonConfig.create(GeneralSettings.class).getCleanupFileExists();
        while (iterator.hasNext()) {
            fp = iterator.next();
            if (fp.getChildren() != null) {
                java.util.List<DownloadLink> removeList = new ArrayList<DownloadLink>();
                it = fp.getChildren().iterator();
                while (it.hasNext()) {
                    localLink = it.next();
                    if (cleanupStartup) {
                        if (FinalLinkState.CheckFinished(localLink.getFinalLinkState())) {
                            logger.info("Remove " + localLink.getView().getDisplayName() + " because Finished and CleanupOnStartup!");
                            removeList.add(localLink);
                            continue;
                        } else if (cleanupFileExists && FinalLinkState.FAILED_EXISTS.equals(localLink.getFinalLinkState())) {
                            logger.info("Remove " + localLink.getView().getDisplayName() + " because FileExists and CleanupOnStartup!");
                            removeList.add(localLink);
                            continue;
                        }
                    }
                    /*
                     * reset not if already exist, offline or finished. plugin errors will be reset here because plugin can be fixed again
                     */
                    processFinalLinkState(localLink);
                    pluginFinder.assignPlugin(localLink, true);
                }
                if (removeList.size() > 0) {
                    fp.getChildren().removeAll(removeList);
                }
            }
            if (fp.getChildren() == null || fp.getChildren().size() == 0) {
                /* remove empty packages */
                iterator.remove();
                continue;
            }
        }
    }

    public void removeListener(final DownloadControllerListener l) {
        eventSender.removeListener(l);
    }

    /**
     * saves List of FilePackages to given File as ZippedJSon
     *
     * @param packages
     * @param file
     */
    private boolean save(java.util.List<FilePackage> packages, File file) throws IOException {
        synchronized (SAVELOADLOCK) {
            if (file == null) {
                if (downloadLists.size() > 0) {
                    String counter = new Regex(downloadLists.get(0).getName(), "downloadList(\\d+)\\.zip").getMatch(0);
                    long count = 1;
                    if (counter != null) {
                        count = Long.parseLong(counter) + 1;
                    }
                    file = Application.getResource("cfg/downloadList" + count + ".zip");
                }
                if (file == null) {
                    file = Application.getResource("cfg/downloadList.zip");
                }
            }
            final int bufferSize;
            if (downloadLists.size() > 0) {
                final long fileLength = downloadLists.get(0).length();
                if (fileLength > 0) {
                    final int paddedFileLength = (((int) fileLength / 32768) + 1) * 32768;
                    bufferSize = Math.max(32768, paddedFileLength);
                } else {
                    bufferSize = 32768;
                }
            } else {
                bufferSize = 32768;
            }
            if (packages != null && file != null) {
                if (file.exists()) {
                    if (file.isDirectory()) {
                        throw new IOException("File " + file + " is a directory");
                    }
                    if (FileCreationManager.getInstance().delete(file, null) == false) {
                        throw new IOException("Could not delete file " + file);
                    }
                } else {
                    if (file.getParentFile().exists() == false && FileCreationManager.getInstance().mkdir(file.getParentFile()) == false) {
                        throw new IOException("Could not create parentFolder for file " + file);
                    }
                }
                /* prepare formatter(001,0001...) for package filenames in zipfiles */
                final String packageFormat;
                if (packages.size() >= 10) {
                    packageFormat = String.format("%%0%dd", (int) Math.log10(packages.size()) + 1);
                } else {
                    packageFormat = "%02d";
                }
                boolean deleteFile = true;
                ZipIOWriter zip = null;
                FileOutputStream fos = null;
                try {
                    fos = new FileOutputStream(file) {
                        @Override
                        public void close() throws IOException {
                            try {
                                if (getChannel().isOpen()) {
                                    getChannel().force(true);
                                }
                            } finally {
                                super.close();
                            }
                        }
                    };
                    zip = new ZipIOWriter(new BufferedOutputStream(fos, bufferSize));
                    int packageIndex = 0;
                    for (FilePackage pkg : packages) {
                        final String packageEntryID = String.format(packageFormat, packageIndex++);
                        /* convert FilePackage to JSon */
                        final FilePackageStorable packageStorable = new FilePackageStorable(pkg);
                        final List<DownloadLinkStorable> linkStorables = new ArrayList<DownloadLinkStorable>(packageStorable.getLinks());
                        packageStorable.getLinks().clear();
                        zip.addByteArry(JSonStorage.serializeToJson(packageStorable).getBytes("UTF-8"), true, "", packageEntryID);
                        final String childFormat;
                        if (linkStorables.size() >= 10) {
                            childFormat = String.format("%%0%dd", (int) Math.log10(packages.size()) + 1);
                        } else {
                            childFormat = "%02d";
                        }
                        int childIndex = 0;
                        for (final DownloadLinkStorable linkStorable : linkStorables) {
                            final String childEntryID = String.format(childFormat, childIndex++);
                            zip.addByteArry(JSonStorage.serializeToJson(linkStorable).getBytes("UTF-8"), true, "", packageEntryID + "_" + childEntryID);
                        }
                    }
                    DownloadControllerStorable dcs = new DownloadControllerStorable();
                    try {
                        /*
                         * set current RootPath of JDownloader, so we can update it when user moves JDownloader folder
                         */
                        dcs.setRootPath(JDUtilities.getJDHomeDirectoryFromEnvironment().getAbsolutePath());
                    } catch (final Throwable e) {
                        /* the method above can throw exceptions, eg in SVN */
                        logger.log(e);
                    }
                    zip.addByteArry(JSonStorage.serializeToJson(dcs).getBytes("UTF-8"), true, "", "extraInfo");
                    /* close ZipIOWriter */
                    zip.close();
                    fos = null;
                    deleteFile = false;
                    try {
                        final int keepXOld = Math.max(JsonConfig.create(GeneralSettings.class).getKeepXOldLists(), 0);
                        if (downloadLists.size() > keepXOld) {
                            for (int removeIndex = downloadLists.size() - 1; removeIndex >= keepXOld; removeIndex--) {
                                final File remove = downloadLists.remove(removeIndex);
                                if (remove != null) {
                                    final boolean delete = FileCreationManager.getInstance().delete(remove, null);
                                    if (LogController.getInstance().isDebugMode()) {
                                        logger.info("Delete outdated DownloadList: " + remove + " " + delete);
                                    }
                                }
                            }
                        }
                    } catch (final Throwable e) {
                        logger.log(e);
                    } finally {
                        downloadLists.add(0, file);
                    }
                    return true;
                } catch (final Throwable e) {
                    logger.log(e);
                } finally {
                    try {
                        if (fos != null) {
                            fos.close();
                        }
                    } catch (final Throwable e) {
                        logger.log(e);
                    }
                    if (deleteFile && file.exists()) {
                        FileCreationManager.getInstance().delete(file, null);
                    }
                }
            }
            return false;
        }
    }

    private boolean allowSaving() {
        return DOWNLOADLIST_LOADED.isReached();
    }

    /**
     * save the current FilePackages/DownloadLinks controlled by this DownloadController
     */
    public void saveDownloadLinks() {
        if (allowSaving()) {
            /* save as new Json ZipFile */
            try {
                save(getPackagesCopy(), null);
            } catch (Throwable e) {
                logger.log(e);
            }
        }
    }

    @Override
    public void nodeUpdated(AbstractNode source, jd.controlling.packagecontroller.AbstractNodeNotifier.NOTIFY notify, Object param) {
        super.nodeUpdated(source, notify, param);
        switch (notify) {
        case PROPERTY_CHANCE:
            if (param instanceof DownloadLinkProperty) {
                DownloadLinkProperty eventPropery = (DownloadLinkProperty) param;
                switch (eventPropery.getProperty()) {
                case VARIANT:
                case URL_CONTENT:
                    dupeController.invalidate();
                    break;
                case NAME:
                case RESET:
                case ENABLED:
                case AVAILABILITY:
                case PRIORITY:
                case EXTRACTION_STATUS:
                case PLUGIN_PROGRESS:
                    eventPropery.getDownloadLink().getParentNode().getView().requestUpdate();
                    break;
                }
            }
            //
            eventSender.fireEvent(new DownloadControllerEventDataUpdate(source, param));
            break;
        case STRUCTURE_CHANCE:
            eventSender.fireEvent(new DownloadControllerEventStructureRefresh(source, param));
            break;
        }

    }

    @Override
    protected void _controllerPackageNodeStructureChanged(FilePackage pkg, QueuePriority priority) {

        eventSender.fireEvent(new DownloadControllerEventStructureRefresh(pkg));
    }

    public void set(final DownloadLinkWalker filter) {
        DownloadController.getInstance().getChildrenByFilter(new AbstractPackageChildrenNodeFilter<DownloadLink>() {

            @Override
            public int returnMaxResults() {
                return 0;
            }

            @Override
            public boolean acceptNode(DownloadLink node) {
                if (filter.accept(node.getFilePackage()) && filter.accept(node)) {
                    filter.handle(node);
                }
                return false;
            }
        });
    }

    /**
     * @param fp
     */
    public static void removePackageIfFinished(final Object asker, final LogSource logger, final FilePackage fp) {
        getInstance().getQueue().add(new QueueAction<Void, RuntimeException>() {

            @Override
            protected Void run() throws RuntimeException {
                if (new DownloadLinkAggregator(fp).isFinished()) {
                    final List<DownloadLink> noVetos = DownloadController.getInstance().askForRemoveVetos(asker, fp);
                    DownloadController.getInstance().removeChildren(noVetos);
                } else {
                    logger.info("Package is not finised");
                }
                return null;
            }
        });
    }

    @Override
    public boolean hasNotificationListener() {
        return true;
    }

    public boolean hasDownloadLinkByID(String linkID) {
        return dupeController.hasID(linkID);
    }

}
