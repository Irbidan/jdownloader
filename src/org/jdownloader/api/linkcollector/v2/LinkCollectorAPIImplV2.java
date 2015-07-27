package org.jdownloader.api.linkcollector.v2;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.swing.Icon;

import jd.controlling.linkchecker.LinkChecker;
import jd.controlling.linkcollector.LinkCollectingJob;
import jd.controlling.linkcollector.LinkCollector;
import jd.controlling.linkcollector.LinkCollector.MoveLinksMode;
import jd.controlling.linkcollector.LinkCollector.MoveLinksSettings;
import jd.controlling.linkcollector.LinkOrigin;
import jd.controlling.linkcollector.LinkOriginDetails;
import jd.controlling.linkcrawler.CheckableLink;
import jd.controlling.linkcrawler.CrawledLink;
import jd.controlling.linkcrawler.CrawledLinkModifier;
import jd.controlling.linkcrawler.CrawledPackage;
import jd.controlling.linkcrawler.CrawledPackageView;
import jd.controlling.linkcrawler.PackageInfo;
import jd.plugins.DownloadLink;

import org.appwork.remoteapi.exceptions.BadParameterException;
import org.appwork.utils.Application;
import org.appwork.utils.IO;
import org.appwork.utils.StringUtils;
import org.appwork.utils.event.queue.QueueAction;
import org.appwork.utils.logging2.LogSource;
import org.appwork.utils.net.Base64InputStream;
import org.jdownloader.api.RemoteAPIController;
import org.jdownloader.api.content.v2.ContentAPIImplV2;
import org.jdownloader.api.utils.PackageControllerUtils;
import org.jdownloader.api.utils.SelectionInfoUtils;
import org.jdownloader.controlling.Priority;
import org.jdownloader.controlling.linkcrawler.LinkVariant;
import org.jdownloader.gui.packagehistorycontroller.DownloadPathHistoryManager;
import org.jdownloader.gui.views.SelectionInfo;
import org.jdownloader.gui.views.components.packagetable.LinkTreeUtils;
import org.jdownloader.logging.LogController;
import org.jdownloader.myjdownloader.client.bindings.CleanupActionOptions;
import org.jdownloader.myjdownloader.client.bindings.PriorityStorable;
import org.jdownloader.myjdownloader.client.bindings.UrlDisplayTypeStorable;
import org.jdownloader.myjdownloader.client.bindings.interfaces.LinkgrabberInterface;
import org.jdownloader.settings.GeneralSettings;
import org.jdownloader.settings.UrlDisplayType;
import org.jdownloader.settings.staticreferences.CFG_GUI;

public class LinkCollectorAPIImplV2 implements LinkCollectorAPIV2 {
    private LogSource                                                 logger;
    private final PackageControllerUtils<CrawledPackage, CrawledLink> packageControllerUtils;

    public LinkCollectorAPIImplV2() {
        RemoteAPIController.validateInterfaces(LinkCollectorAPIV2.class, LinkgrabberInterface.class);
        packageControllerUtils = new PackageControllerUtils<CrawledPackage, CrawledLink>(LinkCollector.getInstance());
        logger = LogController.getInstance().getLogger(LinkCollectorAPIImplV2.class.getName());
    }

    @Override
    public ArrayList<CrawledPackageAPIStorableV2> queryPackages(CrawledPackageQueryStorable queryParams) throws BadParameterException {

        ArrayList<CrawledPackageAPIStorableV2> result = new ArrayList<CrawledPackageAPIStorableV2>();
        LinkCollector lc = LinkCollector.getInstance();

        // filter out packages, if specific packageUUIDs given, else return all packages
        List<CrawledPackage> packages;
        if (queryParams.getPackageUUIDs() != null && queryParams.getPackageUUIDs().length > 0) {
            packages = packageControllerUtils.getPackages(queryParams.getPackageUUIDs());

        } else {
            packages = lc.getPackagesCopy();
        }

        if (packages.size() == 0) {
            return result;
        }

        int startWith = queryParams.getStartAt();
        int maxResults = queryParams.getMaxResults();

        if (startWith > packages.size() - 1) {
            return result;
        }

        if (startWith < 0) {
            startWith = 0;
        }
        if (maxResults < 0) {
            maxResults = packages.size();
        }

        for (int i = startWith; i < startWith + maxResults; i++) {

            final CrawledPackage pkg = packages.get(i);
            boolean readL = pkg.getModifyLock().readLock();
            try {
                CrawledPackageAPIStorableV2 cps = new CrawledPackageAPIStorableV2(pkg);
                final CrawledPackageView view = new CrawledPackageView(pkg);
                view.aggregate();

                if (queryParams.isSaveTo()) {
                    cps.setSaveTo(LinkTreeUtils.getDownloadDirectory(pkg).toString());

                }
                if (queryParams.isBytesTotal()) {
                    cps.setBytesTotal(view.getFileSize());
                }
                if (queryParams.isChildCount()) {
                    cps.setChildCount(view.size());

                }
                if (queryParams.isPriority()) {
                    cps.setPriority(PriorityStorable.valueOf(pkg.getPriorityEnum().name()));
                }
                if (queryParams.isHosts()) {
                    Set<String> hosts = new HashSet<String>();
                    for (CrawledLink cl : pkg.getChildren()) {
                        hosts.add(cl.getHost());
                    }
                    cps.setHosts(hosts.toArray(new String[] {}));

                }

                if (queryParams.isComment()) {
                    cps.setComment(pkg.getComment());
                }
                if (queryParams.isAvailableOfflineCount() || queryParams.isAvailableOnlineCount() || queryParams.isAvailableTempUnknownCount() || queryParams.isAvailableUnknownCount()) {
                    int onlineCount = 0;
                    int offlineCount = 0;
                    int tempUnknown = 0;
                    int unknown = 0;
                    for (CrawledLink cl : pkg.getChildren()) {
                        switch (cl.getLinkState()) {
                        case OFFLINE:
                            offlineCount++;
                            break;
                        case ONLINE:
                            onlineCount++;
                            break;
                        case TEMP_UNKNOWN:
                            tempUnknown++;
                            break;
                        case UNKNOWN:
                            unknown++;
                            break;

                        }
                        if (queryParams.isAvailableOfflineCount()) {
                            cps.setOfflineCount(offlineCount);
                        }
                        if (queryParams.isAvailableOnlineCount()) {
                            cps.setOnlineCount(onlineCount);
                        }
                        if (queryParams.isAvailableTempUnknownCount()) {
                            cps.setTempUnknownCount(tempUnknown);
                        }
                        if (queryParams.isAvailableUnknownCount()) {
                            cps.setUnknownCount(unknown);
                        }

                    }
                }

                if (queryParams.isEnabled()) {
                    boolean enabled = false;
                    for (CrawledLink dl : pkg.getChildren()) {
                        if (dl.isEnabled()) {
                            enabled = true;
                            break;
                        }
                    }
                    cps.setEnabled(enabled);

                }

                result.add(cps);

                if (i == packages.size() - 1) {
                    break;
                }
            } finally {
                pkg.getModifyLock().readUnlock(readL);
            }
        }

        return result;
    }

    @SuppressWarnings("rawtypes")
    @Override
    public ArrayList<CrawledLinkAPIStorableV2> queryLinks(CrawledLinkQueryStorable queryParams) throws BadParameterException {
        ArrayList<CrawledLinkAPIStorableV2> result = new ArrayList<CrawledLinkAPIStorableV2>();
        LinkCollector lc = LinkCollector.getInstance();

        List<CrawledPackage> matched = null;

        if (queryParams.getPackageUUIDs() != null && queryParams.getPackageUUIDs().length > 0) {
            matched = packageControllerUtils.getPackages(queryParams.getPackageUUIDs());
        } else {
            matched = lc.getPackagesCopy();
        }

        // collect children of the selected packages and convert to storables for response
        List<CrawledLink> links = new ArrayList<CrawledLink>();
        for (CrawledPackage pkg : matched) {
            boolean readL = pkg.getModifyLock().readLock();
            try {
                links.addAll(pkg.getChildren());
            } finally {
                pkg.getModifyLock().readUnlock(readL);
            }
        }

        if (links.isEmpty()) {
            return result;
        }

        int startWith = queryParams.getStartAt();
        int maxResults = queryParams.getMaxResults();

        if (startWith > links.size() - 1) {
            return result;
        }
        if (startWith < 0) {
            startWith = 0;
        }
        if (maxResults < 0) {
            maxResults = links.size();
        }
        ContentAPIImplV2 contentAPI = RemoteAPIController.getInstance().getContentAPI();
        for (int i = startWith; i < Math.min(startWith + maxResults, links.size()); i++) {

            CrawledLink cl = links.get(i);
            CrawledLinkAPIStorableV2 cls = new CrawledLinkAPIStorableV2(cl);
            if (queryParams.isPriority()) {
                cls.setPriority(org.jdownloader.myjdownloader.client.bindings.PriorityStorable.valueOf(cl.getPriority().name()));
            }
            if (queryParams.isVariantID() || queryParams.isVariantName() || queryParams.isVariantIcon() || queryParams.isVariants()) {
                try {
                    if (cl.hasVariantSupport()) {
                        if (queryParams.isVariants()) {
                            cls.setVariants(true);
                        }
                        if (queryParams.isVariantID() || queryParams.isVariantName() || queryParams.isVariantIcon()) {
                            LinkVariant v = cl.getDownloadLink().getDefaultPlugin().getActiveVariantByLink(cl.getDownloadLink());
                            LinkVariantStorableV2 s = new LinkVariantStorableV2();
                            if (v != null) {
                                if (queryParams.isVariantID()) {
                                    s.setId(v._getUniqueId());
                                }
                                if (queryParams.isVariantName()) {
                                    s.setName(v._getName());
                                }
                                if (queryParams.isVariantIcon()) {
                                    Icon icon = v._getIcon();
                                    if (icon != null) {
                                        s.setIconKey(contentAPI.getIconKey(icon));
                                    }
                                }
                            }
                            cls.setVariant(s);
                        }

                    }
                } catch (Throwable e) {
                    logger.log(e);
                }
            }
            if (queryParams.isComment()) {
                cls.setComment(cl.getComment());
            }
            if (queryParams.isBytesTotal()) {
                cls.setBytesTotal(cl.getSize());
            }
            if (queryParams.isHost()) {
                cls.setHost(cl.getHost());
            }
            if (queryParams.isAvailability()) {
                cls.setAvailability(cl.getLinkState());

            }
            if (queryParams.isUrl()) {
                cls.setUrl(cl.getURL());

            }
            if (queryParams.isEnabled()) {
                cls.setEnabled(cl.isEnabled());
            }
            cls.setPackageUUID(cl.getParentNode().getUniqueID().getID());

            result.add(cls);
        }

        return result;
    }

    @Override
    public int getPackageCount() {
        return LinkCollector.getInstance().size();
    }

    @Override
    public void addLinks(final AddLinksQueryStorable query) {

        LinkCollector lc = LinkCollector.getInstance();
        Priority p = Priority.DEFAULT;

        try {
            p = Priority.valueOf(query.getPriority().name());
        } catch (Throwable e) {
            logger.log(e);
        }
        final Priority fp = p;
        LinkCollectingJob lcj = new LinkCollectingJob(new LinkOriginDetails(LinkOrigin.MYJD, null/* add useragent? */), query.getLinks());
        HashSet<String> extPws = null;
        if (StringUtils.isNotEmpty(query.getExtractPassword())) {
            extPws = new HashSet<String>();
            extPws.add(query.getExtractPassword());
        }
        final HashSet<String> finalExtPws = extPws;
        final CrawledLinkModifier modifier = new CrawledLinkModifier() {

            @Override
            public void modifyCrawledLink(CrawledLink link) {
                if (finalExtPws != null && finalExtPws.size() > 0) {
                    link.getArchiveInfo().getExtractionPasswords().addAll(finalExtPws);
                }
                if (StringUtils.isNotEmpty(query.getPackageName())) {
                    PackageInfo packageInfo = link.getDesiredPackageInfo();
                    if (packageInfo == null) {
                        packageInfo = new PackageInfo();
                    }
                    packageInfo.setName(query.getPackageName());
                    packageInfo.setIgnoreVarious(true);
                    packageInfo.setUniqueId(null);
                    link.setDesiredPackageInfo(packageInfo);
                }
                link.setPriority(fp);
                if (StringUtils.isNotEmpty(query.getDestinationFolder())) {
                    PackageInfo packageInfo = link.getDesiredPackageInfo();
                    if (packageInfo == null) {
                        packageInfo = new PackageInfo();
                    }
                    packageInfo.setDestinationFolder(query.getDestinationFolder());
                    packageInfo.setIgnoreVarious(true);
                    packageInfo.setUniqueId(null);
                    link.setDesiredPackageInfo(packageInfo);
                }
                DownloadLink dlLink = link.getDownloadLink();
                if (dlLink != null) {
                    if (StringUtils.isNotEmpty(query.getDownloadPassword())) {
                        dlLink.setDownloadPassword(query.getDownloadPassword());
                    }
                }
                if (query.isAutostart()) {
                    link.setAutoConfirmEnabled(true);
                    link.setAutoStartEnabled(true);
                }
            }
        };
        lcj.setCrawledLinkModifierPrePackagizer(modifier);
        if (StringUtils.isNotEmpty(query.getDestinationFolder()) || StringUtils.isNotEmpty(query.getPackageName())) {
            lcj.setCrawledLinkModifierPostPackagizer(modifier);
        }
        lc.addCrawlerJob(lcj);

    }

    @Override
    public long getChildrenChanged(long structureWatermark) {
        return packageControllerUtils.getChildrenChanged(structureWatermark);
    }

    @Override
    public void moveToDownloadlist(final long[] linkIds, final long[] packageIds) throws BadParameterException {
        SelectionInfo<CrawledPackage, CrawledLink> selectionInfo = packageControllerUtils.getSelectionInfo(linkIds, packageIds);
        LinkCollector.getInstance().moveLinksToDownloadList(new MoveLinksSettings(MoveLinksMode.MANUAL, true, null, null), selectionInfo);
    }

    @Override
    public void removeLinks(final long[] linkIds, final long[] packageIds) throws BadParameterException {
        packageControllerUtils.remove(linkIds, packageIds);
    }

    @Override
    public void renameLink(long linkId, String newName) throws BadParameterException {
        final List<CrawledLink> children = packageControllerUtils.getChildren(linkId);
        if (children.size() > 0) {
            children.get(0).setName(newName);
        }
    }

    @Override
    public void renamePackage(long packageId, String newName) throws BadParameterException {
        final List<CrawledPackage> selectionInfo = packageControllerUtils.getPackages(packageId);
        if (selectionInfo.size() > 0) {
            final CrawledPackage lc = selectionInfo.get(0);
            if (lc != null) {
                lc.setName(newName);
            }
        }
    }

    @Override
    public void setEnabled(boolean enabled, final long[] linkIds, final long[] packageIds) throws BadParameterException {
        packageControllerUtils.setEnabled(enabled, linkIds, packageIds);
    }

    @Override
    public void movePackages(long[] packageIds, long afterDestPackageId) throws BadParameterException {
        packageControllerUtils.movePackages(packageIds, afterDestPackageId);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void moveLinks(long[] linkIds, long afterLinkID, long destPackageID) throws BadParameterException {
        packageControllerUtils.moveChildren(linkIds, afterLinkID, destPackageID);
    }

    @Override
    public List<String> getDownloadFolderHistorySelectionBase() {
        return DownloadPathHistoryManager.getInstance().listPaths(org.appwork.storage.config.JsonConfig.create(GeneralSettings.class).getDefaultDownloadFolder());

    }

    @Override
    public List<LinkVariantStorableV2> getVariants(long linkid) throws BadParameterException {
        final ArrayList<LinkVariantStorableV2> ret = new ArrayList<LinkVariantStorableV2>();
        final List<CrawledLink> children = packageControllerUtils.getChildren(linkid);
        if (children.size() > 0) {
            final CrawledLink cl = children.get(0);
            for (LinkVariant lv : cl.getDownloadLink().getDefaultPlugin().getVariantsByLink(cl.getDownloadLink())) {
                ret.add(new LinkVariantStorableV2(lv._getUniqueId(), CFG_GUI.EXTENDED_VARIANT_NAMES_ENABLED.isEnabled() ? lv._getExtendedName() : lv._getName()));
            }
        }
        return ret;
    }

    @Override
    public void setVariant(long linkid, String variantID) throws BadParameterException {
        final List<CrawledLink> children = packageControllerUtils.getChildren(linkid);
        if (children.size() > 0) {
            final CrawledLink cl = children.get(0);
            if (cl != null) {
                for (LinkVariant lv : cl.getDownloadLink().getDefaultPlugin().getVariantsByLink(cl.getDownloadLink())) {
                    if (lv._getUniqueId().equals(variantID)) {
                        LinkCollector.getInstance().setActiveVariantForLink(cl, lv);
                        return;
                    }
                }
                throw new BadParameterException("Unknown variantID");
            }
        }
    }

    @Override
    public void addVariantCopy(long linkid, final long destinationAfterLinkID, final long destinationPackageID, final String variantID) throws BadParameterException {
        List<CrawledLink> children = packageControllerUtils.getChildren(linkid);
        if (children.size() > 0) {
            final CrawledLink link = children.get(0);
            if (link != null) {
                // move and add
                LinkCollector.getInstance().getQueue().add(new QueueAction<Void, BadParameterException>() {

                    @Override
                    protected Void run() throws BadParameterException {
                        // search variant by id
                        LinkVariant v = null;
                        for (LinkVariant lv : link.getDownloadLink().getDefaultPlugin().getVariantsByLink(link.getDownloadLink())) {
                            if (lv._getUniqueId().equals(variantID)) {
                                v = lv;
                                break;
                            }
                        }
                        if (v == null) {
                            throw new BadParameterException("Unknown variantID");
                        }

                        // create new downloadlink
                        final DownloadLink dllink = new DownloadLink(link.getDownloadLink().getDefaultPlugin(), link.getDownloadLink().getView().getDisplayName(), link.getDownloadLink().getHost(), link.getDownloadLink().getPluginPatternMatcher(), true);
                        dllink.setProperties(link.getDownloadLink().getProperties());

                        // create crawledlink
                        final CrawledLink cl = new CrawledLink(dllink);

                        final ArrayList<CrawledLink> list = new ArrayList<CrawledLink>();
                        list.add(cl);

                        cl.getDownloadLink().getDefaultPlugin().setActiveVariantByLink(cl.getDownloadLink(), v);

                        // check if package already contains this variant

                        boolean readL = link.getParentNode().getModifyLock().readLock();

                        try {

                            for (CrawledLink cLink : link.getParentNode().getChildren()) {
                                if (dllink.getLinkID().equals(cLink.getLinkID())) {
                                    throw new BadParameterException("Variant is already in this package");
                                }
                            }
                        } finally {
                            link.getParentNode().getModifyLock().readUnlock(readL);
                        }

                        if (destinationPackageID < 0) {
                            LinkCollector.getInstance().moveOrAddAt(link.getParentNode(), list, link.getParentNode().indexOf(link) + 1);
                        } else {

                            LinkCollector dlc = LinkCollector.getInstance();
                            CrawledLink afterLink = null;
                            CrawledPackage destpackage = null;
                            if (destinationAfterLinkID > 0) {
                                List<CrawledLink> children = packageControllerUtils.getChildren(destinationAfterLinkID);
                                if (children.size() > 0) {
                                    afterLink = children.get(0);
                                }
                            }
                            if (destinationPackageID > 0) {
                                List<CrawledPackage> packages = packageControllerUtils.getPackages(destinationPackageID);
                                if (packages.size() > 0) {
                                    destpackage = packages.get(0);
                                }
                            }
                            dlc.move(list, destpackage, afterLink);
                        }

                        java.util.List<CheckableLink> checkableLinks = new ArrayList<CheckableLink>(1);
                        checkableLinks.add(cl);
                        LinkChecker<CheckableLink> linkChecker = new LinkChecker<CheckableLink>(true);
                        linkChecker.check(checkableLinks);
                        return null;
                    }
                });
            }
        }
    }

    public static InputStream getInputStream(String dataURL) throws IOException {
        final int base64Index = dataURL.indexOf(";base64,");
        if (base64Index == -1 || base64Index + 8 >= dataURL.length()) {
            throw new IOException("Invalid DataURL: " + dataURL);
        }
        return new Base64InputStream(new ByteArrayInputStream(dataURL.substring(base64Index + 8).getBytes("UTF-8")));
    }

    @Override
    public void addContainer(String type, String content) {
        String fileName = null;
        if ("DLC".equalsIgnoreCase(type)) {
            fileName = "linkcollectorDLCAPI" + System.nanoTime() + ".dlc";
        } else if ("RSDF".equalsIgnoreCase(type)) {
            fileName = "linkcollectorDLCAPI" + System.nanoTime() + ".rsdf";
        } else if ("CCF".equalsIgnoreCase(type)) {
            fileName = "linkcollectorDLCAPI" + System.nanoTime() + ".ccf";
        }
        if (fileName != null) {
            try {
                File tmp = Application.getTempResource(fileName);
                byte[] write = IO.readStream(-1, getInputStream(content));
                IO.writeToFile(tmp, write);
                LinkCollector.getInstance().addCrawlerJob(new LinkCollectingJob(new LinkOriginDetails(LinkOrigin.MYJD), tmp.toURI().toString()));
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void setPriority(PriorityStorable priority, long[] linkIds, long[] packageIds) throws BadParameterException {
        final org.jdownloader.controlling.Priority jdPriority = org.jdownloader.controlling.Priority.valueOf(priority.name());
        List<CrawledLink> children = packageControllerUtils.getChildren(linkIds);
        List<CrawledPackage> pkgs = packageControllerUtils.getPackages(packageIds);
        for (CrawledLink dl : children) {
            dl.setPriority(jdPriority);
        }
        for (CrawledPackage pkg : pkgs) {
            pkg.setPriorityEnum(jdPriority);
        }
    }

    @Override
    public void startOnlineStatusCheck(long[] linkIds, long[] packageIds) throws BadParameterException {
        packageControllerUtils.startOnlineStatusCheck(linkIds, packageIds);
    }

    @Override
    public Map<String, List<Long>> getDownloadUrls(final long[] linkIds, final long[] packageIds, UrlDisplayTypeStorable[] urlDisplayTypes) throws BadParameterException {
        final List<UrlDisplayType> types = new ArrayList<UrlDisplayType>();
        for (final UrlDisplayTypeStorable urlDisplayType : urlDisplayTypes) {
            try {
                types.add(UrlDisplayType.valueOf(urlDisplayType.name()));
            } catch (Exception e) {
                throw new BadParameterException(e.getMessage());
            }
        }
        return SelectionInfoUtils.getURLs(packageControllerUtils.getSelectionInfo(linkIds, packageIds), types);
    }

    @Override
    public void movetoNewPackage(long[] linkIds, long[] pkgIds, String newPkgName, String downloadPath) throws BadParameterException {
        packageControllerUtils.movetoNewPackage(linkIds, pkgIds, newPkgName, downloadPath);
    }

    @Override
    public void setDownloadDirectory(String directory, long[] packageIds) throws BadParameterException {
        if (StringUtils.isEmpty(directory)) {
            throw new BadParameterException("invalid dir");
        }
        packageControllerUtils.setDownloadDirectory(directory, packageIds);
    }

    @Override
    public void splitPackageByHoster(long[] linkIds, long[] pkgIds) {
        packageControllerUtils.splitPackageByHoster(linkIds, pkgIds);
    }

    @Override
    public void cleanup(final long[] linkIds, final long[] packageIds, final CleanupActionOptions.Action action, final CleanupActionOptions.Mode mode, final CleanupActionOptions.SelectionType selectionType) throws BadParameterException {
        packageControllerUtils.cleanup(linkIds, packageIds, action, mode, selectionType);
    }
}