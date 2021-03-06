package jd.controlling.linkcrawler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;

import jd.controlling.packagecontroller.AbstractNode;
import jd.controlling.packagecontroller.ChildrenView;
import jd.plugins.DownloadLink;

import org.appwork.utils.StringUtils;
import org.jdownloader.DomainInfo;
import org.jdownloader.gui.translate._GUI;
import org.jdownloader.gui.views.components.packagetable.PackageControllerTableModelData.PackageControllerTableModelDataPackage;
import org.jdownloader.gui.views.downloads.columns.AvailabilityColumn;
import org.jdownloader.myjdownloader.client.json.AvailableLinkState;

public class CrawledPackageView extends ChildrenView<CrawledLink> {

    protected volatile long                fileSize                 = 0;
    private volatile DomainInfo[]          domainInfos              = new DomainInfo[0];
    protected volatile boolean             enabled                  = false;
    private volatile int                   offline                  = 0;
    private volatile int                   online                   = 0;
    private volatile int                   items                    = 0;
    private final AtomicLong               updatesRequired          = new AtomicLong(0);
    private volatile long                  updatesDone              = -1;

    private volatile String                commonSourceUrl;
    private volatile String                availabilityColumnString = null;
    private volatile ChildrenAvailablility availability             = ChildrenAvailablility.UNKNOWN;
    private final CrawledPackage           pkg;

    public CrawledPackageView() {
        this(null);
    }

    public CrawledPackageView(CrawledPackage pkg) {
        this.pkg = pkg;
    }

    @Override
    public CrawledPackageView aggregate() {
        if (pkg != null) {
            final boolean readL = pkg.getModifyLock().readLock();
            try {
                return setItems(pkg.getChildren());
            } finally {
                pkg.getModifyLock().readUnlock(readL);
            }
        } else {
            synchronized (this) {
                Temp tmp = new Temp();
                /* this is called for repaint, so only update values that could have changed for existing items */
                final PackageControllerTableModelDataPackage tableModelDataPackage = getTableModelDataPackage();
                int size = 0;
                if (tableModelDataPackage != null) {
                    for (AbstractNode item : tableModelDataPackage.getVisibleChildren()) {
                        size++;
                        addtoTmp(tmp, (CrawledLink) item);
                    }
                }
                writeTmpToFields(tmp);
                updateAvailability(size, tmp.newOffline, tmp.newOnline);
                availabilityColumnString = _GUI._.AvailabilityColumn_getStringValue_object_(tmp.newOnline, size);
            }
            return this;
        }
    }

    private class Temp {
        final HashMap<String, Long> names             = new HashMap<String, Long>();
        final TreeSet<DomainInfo>   domains           = new TreeSet<DomainInfo>();
        int                         newOnline         = 0;
        long                        newFileSize       = 0;
        boolean                     newEnabled        = false;
        int                         newOffline        = 0;

        String                      sameSource        = null;
        boolean                     sameSourceFullUrl = true;
        long                        lupdatesRequired  = updatesRequired.get();
    }

    @Override
    public CrawledPackageView setItems(List<CrawledLink> items) {
        final Temp tmp = new Temp();
        synchronized (this) {
            /* this is called for tablechanged, so update everything for given items */
            if (items != null) {
                for (CrawledLink item : items) {
                    // domain
                    tmp.domains.add(item.getDomainInfo());
                    addtoTmp(tmp, item);
                }
            }
            writeTmpToFields(tmp);
            ArrayList<DomainInfo> lst = new ArrayList<DomainInfo>(tmp.domains);
            Collections.sort(lst, new Comparator<DomainInfo>() {

                @Override
                public int compare(DomainInfo o1, DomainInfo o2) {
                    return o1.getTld().compareTo(o2.getTld());
                }
            });
            domainInfos = lst.toArray(new DomainInfo[] {});
            if (items == null) {
                this.items = 0;
            } else {
                this.items = items.size();
            }
            updateAvailability(this.items, tmp.newOffline, tmp.newOnline);
            availabilityColumnString = _GUI._.AvailabilityColumn_getStringValue_object_(tmp.newOnline, this.items);
        }
        return this;
    }

    protected void writeTmpToFields(Temp tmp) {
        this.commonSourceUrl = tmp.sameSource;
        if (!tmp.sameSourceFullUrl) {
            commonSourceUrl += "[...]";
        }
        fileSize = tmp.newFileSize;
        enabled = tmp.newEnabled;
        offline = tmp.newOffline;
        online = tmp.newOnline;
        updatesDone = tmp.lupdatesRequired;
    }

    protected void addtoTmp(Temp tmp, CrawledLink link) {
        DownloadLink dlLink = link.getDownloadLink();
        String sourceUrl = dlLink.getView().getDisplayUrl();

        if (sourceUrl != null) {
            tmp.sameSource = StringUtils.getCommonalities(tmp.sameSource, sourceUrl);
            tmp.sameSourceFullUrl = tmp.sameSourceFullUrl && tmp.sameSource.equals(sourceUrl);
        }

        // enabled
        if (link.isEnabled()) {
            tmp.newEnabled = true;
        }
        if (link.getLinkState() == AvailableLinkState.OFFLINE) {
            // offline
            tmp.newOffline++;
        } else if (link.getLinkState() == AvailableLinkState.ONLINE) {
            // online
            tmp.newOnline++;
        }
        String name = link.getName();
        Long size = tmp.names.get(name);
        /* we use the largest filesize */
        long itemSize = Math.max(0, link.getSize());
        if (size == null) {
            tmp.names.put(name, itemSize);
            tmp.newFileSize += itemSize;
        } else if (size < itemSize) {
            tmp.newFileSize -= size;
            tmp.names.put(name, itemSize);
            tmp.newFileSize += itemSize;
        }

    }

    private final void updateAvailability(int size, int offline, int online) {
        if (online == size) {
            availability = ChildrenAvailablility.ONLINE;
            return;
        }
        if (offline == size) {
            availability = ChildrenAvailablility.OFFLINE;
            return;
        }
        if ((offline == 0 && online == 0) || (online == 0 && offline > 0)) {
            availability = ChildrenAvailablility.UNKNOWN;
            return;
        }
        availability = ChildrenAvailablility.MIXED;
        return;
    }

    public String getCommonSourceUrl() {
        return commonSourceUrl;
    }

    public DomainInfo[] getDomainInfos() {
        return domainInfos;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getOfflineCount() {
        return offline;
    }

    public int getOnlineCount() {
        return online;
    }

    public long getFileSize() {
        return fileSize;
    }

    @Override
    public void requestUpdate() {
        updatesRequired.incrementAndGet();
    }

    @Override
    public boolean updateRequired() {
        return updatesRequired.get() != updatesDone;
    }

    @Override
    public ChildrenAvailablility getAvailability() {
        return availability;
    }

    @Override
    public String getMessage(Object requestor) {
        if (requestor instanceof AvailabilityColumn) {
            return availabilityColumnString;
        }
        return null;
    }

    @Override
    public int size() {
        return items;
    }

}
