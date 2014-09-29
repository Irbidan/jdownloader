package jd.controlling.linkcrawler;

import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import jd.controlling.linkcollector.LinkCollectingJob;
import jd.controlling.linkcollector.LinkCollector.JobLinkCrawler;
import jd.controlling.linkcollector.LinknameCleaner;
import jd.http.Browser;
import jd.parser.html.HTMLParser;
import jd.plugins.CryptedLink;
import jd.plugins.DownloadLink;
import jd.plugins.FilePackage;
import jd.plugins.PluginForDecrypt;
import jd.plugins.PluginForHost;
import jd.plugins.PluginsC;

import org.appwork.scheduler.DelayedRunnable;
import org.appwork.storage.config.JsonConfig;
import org.appwork.utils.Files;
import org.appwork.utils.Regex;
import org.appwork.utils.StringUtils;
import org.appwork.utils.logging2.LogSource;
import org.jdownloader.controlling.UniqueAlltimeID;
import org.jdownloader.controlling.UrlProtection;
import org.jdownloader.logging.LogController;
import org.jdownloader.plugins.controller.LazyPluginClass;
import org.jdownloader.plugins.controller.PluginClassLoader;
import org.jdownloader.plugins.controller.PluginClassLoader.PluginClassLoaderChild;
import org.jdownloader.plugins.controller.UpdateRequiredClassNotFoundException;
import org.jdownloader.plugins.controller.container.ContainerPluginController;
import org.jdownloader.plugins.controller.crawler.CrawlerPluginController;
import org.jdownloader.plugins.controller.crawler.LazyCrawlerPlugin;
import org.jdownloader.plugins.controller.host.HostPluginController;
import org.jdownloader.plugins.controller.host.LazyHostPlugin;
import org.jdownloader.settings.GeneralSettings;

public class LinkCrawler {

    private final static String                     DIRECT_HTTP                 = "DirectHTTP";
    private final static String                     HTTP_LINKS                  = "http links";
    private LazyHostPlugin                          directHTTP                  = null;
    private LazyHostPlugin                          ftp                         = null;
    private java.util.List<CrawledLink>             crawledLinks                = new ArrayList<CrawledLink>();
    private AtomicInteger                           crawledLinksCounter         = new AtomicInteger(0);
    private java.util.List<CrawledLink>             filteredLinks               = new ArrayList<CrawledLink>();
    private AtomicInteger                           filteredLinksCounter        = new AtomicInteger(0);
    private java.util.List<CrawledLink>             brokenLinks                 = new ArrayList<CrawledLink>();
    private AtomicInteger                           brokenLinksCounter          = new AtomicInteger(0);
    private java.util.List<CrawledLink>             unhandledLinks              = new ArrayList<CrawledLink>();
    private final AtomicInteger                     unhandledLinksCounter       = new AtomicInteger(0);
    private final AtomicInteger                     processedLinksCounter       = new AtomicInteger(0);

    private final AtomicInteger                     crawler                     = new AtomicInteger(0);
    private final static AtomicInteger              CRAWLER                     = new AtomicInteger(0);
    private final ConcurrentHashMap<String, Object> duplicateFinderContainer;
    private final ConcurrentHashMap<String, Object> duplicateFinderCrawler;
    private final ConcurrentHashMap<String, Object> duplicateFinderFinal;
    private final ConcurrentHashMap<String, Object> duplicateFinderDeep;
    private LinkCrawlerHandler                      handler                     = null;
    protected static final ThreadPoolExecutor       threadPool;

    private LinkCrawlerFilter                       filter                      = null;
    private final AtomicBoolean                     allowCrawling               = new AtomicBoolean(true);
    protected final AtomicInteger                   crawlerGeneration           = new AtomicInteger(0);
    private final LinkCrawler                       parentCrawler;
    private final long                              created;

    public final static String                      PACKAGE_ALLOW_MERGE         = "ALLOW_MERGE";
    public final static String                      PACKAGE_CLEANUP_NAME        = "CLEANUP_NAME";
    public final static String                      PACKAGE_IGNORE_VARIOUS      = "PACKAGE_IGNORE_VARIOUS";
    public static final UniqueAlltimeID             PERMANENT_OFFLINE_ID        = new UniqueAlltimeID();
    private boolean                                 doDuplicateFinderFinalCheck = true;
    private final List<LazyHostPlugin>              pHosts;
    private List<LazyCrawlerPlugin>                 cHosts;
    protected final PluginClassLoaderChild          classLoader;
    private boolean                                 directHttpEnabled           = true;
    private final String                            defaultDownloadFolder;

    public void setDirectHttpEnabled(boolean directHttpEnabled) {
        this.directHttpEnabled = directHttpEnabled;
    }

    protected final static ScheduledExecutorService TIMINGQUEUE = DelayedRunnable.getNewScheduledExecutorService();

    public boolean isDoDuplicateFinderFinalCheck() {
        if (parentCrawler != null) {
            parentCrawler.isDoDuplicateFinderFinalCheck();
        }
        return doDuplicateFinderFinalCheck;
    }

    protected Long getDefaultAverageRuntime() {
        return null;
    }

    /*
     * customized comparator we use to prefer faster decrypter plugins over slower ones
     */

    static {
        final int maxThreads = Math.max(JsonConfig.create(LinkCrawlerConfig.class).getMaxThreads(), 1);
        final int keepAlive = Math.max(JsonConfig.create(LinkCrawlerConfig.class).getThreadKeepAlive(), 100);
        /**
         * PriorityBlockingQueue leaks last Item for some java versions
         * 
         * http://bugs.java.com/bugdatabase/view_bug.do?bug_id=7161229
         */
        threadPool = new ThreadPoolExecutor(0, maxThreads, keepAlive, TimeUnit.MILLISECONDS, new PriorityBlockingQueue<Runnable>(100, new Comparator<Runnable>() {
            public int compare(Runnable o1, Runnable o2) {
                if (o1 == o2) {
                    return 0;
                }
                long l1 = ((LinkCrawlerRunnable) o1).getAverageRuntime();
                long l2 = ((LinkCrawlerRunnable) o2).getAverageRuntime();
                return (l1 < l2) ? -1 : ((l1 == l2) ? 0 : 1);
            }
        }), new ThreadFactory() {

            public Thread newThread(Runnable r) {
                /*
                 * our thread factory so we have logger,browser settings available
                 */
                return new LinkCrawlerThread(r);
            }

        }, new ThreadPoolExecutor.AbortPolicy()) {

            @Override
            protected void beforeExecute(Thread t, Runnable r) {
                super.beforeExecute(t, r);
                /*
                 * WORKAROUND for stupid SUN /ORACLE way of "how a threadpool should work" !
                 */
                int working = threadPool.getActiveCount();
                int active = threadPool.getPoolSize();
                int max = threadPool.getMaximumPoolSize();
                if (active < max) {
                    if (working == active) {
                        /*
                         * we can increase max pool size so new threads get started
                         */
                        threadPool.setCorePoolSize(Math.min(max, active + 1));
                    }
                }
            }

        };
        threadPool.allowCoreThreadTimeOut(true);
    }

    private static LinkCrawlerEventSender EVENTSENDER = new LinkCrawlerEventSender();

    public static LinkCrawlerEventSender getGlobalEventSender() {
        return EVENTSENDER;
    }

    public LinkCrawler() {
        this(true, true);
    }

    public static LinkCrawler newInstance() {
        final LinkCrawler lc;
        if (Thread.currentThread() instanceof LinkCrawlerThread) {
            final LinkCrawlerThread thread = (LinkCrawlerThread) (Thread.currentThread());
            Object owner = thread.getCurrentOwner();
            final CrawledLink source;
            if (owner instanceof PluginForDecrypt) {
                source = ((PluginForDecrypt) owner).getCurrentLink();
            } else {
                source = null;
            }
            final LinkCrawler parent = thread.getCurrentLinkCrawler();
            lc = new LinkCrawler(false, false) {

                @Override
                protected CrawledLink crawledLinkFactorybyURL(String url) {
                    final CrawledLink ret = new CrawledLink(url);
                    if (source != null) {
                        ret.setSourceLink(source);
                    }
                    return ret;
                }

                @Override
                public int getCrawlerGeneration(boolean thisGeneration) {
                    if (!thisGeneration && parent != null) {
                        return Math.max(crawlerGeneration.get(), parent.getCrawlerGeneration(false));
                    }
                    return crawlerGeneration.get();
                }

                @Override
                public List<LazyCrawlerPlugin> getCrawlerPlugins() {
                    if (parent != null) {
                        return parent.getCrawlerPlugins();
                    }
                    return super.getCrawlerPlugins();
                }

                @Override
                protected boolean distributeCrawledLink(CrawledLink crawledLink) {
                    return crawledLink != null && crawledLink.getSourceUrls() == null;
                }

                @Override
                protected void postprocessFinalCrawledLink(CrawledLink link) {
                }

                @Override
                protected void preprocessFinalCrawledLink(CrawledLink link) {
                }

            };
        } else {
            lc = new LinkCrawler(true, true);
        }
        return lc;
    }

    protected PluginClassLoaderChild getPluginClassLoaderChild() {
        return classLoader;
    }

    public LinkCrawler(boolean connectParentCrawler, boolean avoidDuplicates) {
        setFilter(defaultFilterFactory());
        if (connectParentCrawler && Thread.currentThread() instanceof LinkCrawlerThread) {
            /* forward crawlerGeneration from parent to this child */
            LinkCrawlerThread thread = (LinkCrawlerThread) Thread.currentThread();
            parentCrawler = thread.getCurrentLinkCrawler();
            classLoader = parentCrawler.getPluginClassLoaderChild();
            this.pHosts = parentCrawler.pHosts;
            this.directHTTP = parentCrawler.directHTTP;
            this.ftp = parentCrawler.ftp;
            this.directHttpEnabled = parentCrawler.directHttpEnabled;
            this.defaultDownloadFolder = parentCrawler.defaultDownloadFolder;
            duplicateFinderContainer = parentCrawler.duplicateFinderContainer;
            duplicateFinderCrawler = parentCrawler.duplicateFinderCrawler;
            duplicateFinderFinal = parentCrawler.duplicateFinderFinal;
            duplicateFinderDeep = parentCrawler.duplicateFinderDeep;
            setHandler(parentCrawler.getHandler());
        } else {
            duplicateFinderContainer = new ConcurrentHashMap<String, Object>(8, 0.9f, 1);
            duplicateFinderCrawler = new ConcurrentHashMap<String, Object>(8, 0.9f, 1);
            duplicateFinderFinal = new ConcurrentHashMap<String, Object>(8, 0.9f, 1);
            duplicateFinderDeep = new ConcurrentHashMap<String, Object>(8, 0.9f, 1);
            setHandler(defaulHandlerFactory());
            defaultDownloadFolder = JsonConfig.create(GeneralSettings.class).getDefaultDownloadFolder();
            parentCrawler = null;
            classLoader = PluginClassLoader.getInstance().getChild();
            pHosts = new ArrayList<LazyHostPlugin>(HostPluginController.getInstance().list());
            for (LazyHostPlugin pHost : pHosts) {
                if (directHTTP == null && HTTP_LINKS.equals(pHost.getDisplayName())) {
                    /* for direct access to the directhttp plugin */
                    // we have at least 2 directHTTP entries in pHost. each one listens to a different regex
                    // the one we found here listens to "https?viajd://[\\w\\.:\\-@]*/.*\\.(jdeatme|3gp|7zip|7z|abr...
                    // the other listens to directhttp://.+
                    directHTTP = pHost;
                } else if (ftp == null && "ftp".equals(pHost.getDisplayName())) {
                    /* for generic ftp sites */
                    ftp = pHost;
                }
                if (ftp != null && directHTTP != null) {
                    break;
                }
            }
            if (ftp != null) {
                /* generic ftp handling is done at the end */
                /* remove from list, then we don't have to compare each single plugin each round */
                pHosts.remove(ftp);
            }
        }
        this.created = System.currentTimeMillis();
        this.doDuplicateFinderFinalCheck = avoidDuplicates;
    }

    public long getCreated() {
        if (parentCrawler != null) {
            return parentCrawler.getCreated();
        }
        return created;
    }

    /**
     * returns the generation of this LinkCrawler if thisGeneration is true.
     * 
     * if a parent LinkCrawler does exist and thisGeneration is false, we return the older generation of the parent LinkCrawler or this
     * child
     * 
     * @param thisGeneration
     * @return
     */
    public int getCrawlerGeneration(boolean thisGeneration) {
        if (!thisGeneration && parentCrawler != null) {
            return Math.max(crawlerGeneration.get(), parentCrawler.getCrawlerGeneration(false));
        }
        return crawlerGeneration.get();
    }

    protected CrawledLink crawledLinkFactorybyURL(String url) {
        return new CrawledLink(url);
    }

    public void crawl(String text) {
        crawl(text, null, false);
    }

    private volatile Set<String> crawlerPluginBlacklist = new HashSet<String>();
    private volatile Set<String> hostPluginBlacklist    = new HashSet<String>();

    public void setCrawlerPluginBlacklist(String[] list) {
        HashSet<String> lcrawlerPluginBlacklist = new HashSet<String>();
        if (list != null) {
            for (String s : list) {
                lcrawlerPluginBlacklist.add(s);
            }
        }
        this.crawlerPluginBlacklist = lcrawlerPluginBlacklist;
    }

    public boolean isBlacklisted(LazyCrawlerPlugin plugin) {
        if (parentCrawler != null && parentCrawler.isBlacklisted(plugin)) {
            return true;
        }
        return crawlerPluginBlacklist.contains(plugin.getDisplayName());
    }

    public boolean isBlacklisted(LazyHostPlugin plugin) {
        if (parentCrawler != null && parentCrawler.isBlacklisted(plugin)) {
            return true;
        }
        return hostPluginBlacklist.contains(plugin.getDisplayName());
    }

    public void setHostPluginBlacklist(String[] list) {
        HashSet<String> lhostPluginBlacklist = new HashSet<String>();
        if (list != null) {
            for (String s : list) {
                lhostPluginBlacklist.add(s);
            }
        }
        this.hostPluginBlacklist = lhostPluginBlacklist;
    }

    public void crawl(final String text, final String url, final boolean allowDeep) {
        if (!StringUtils.isEmpty(text)) {
            if (checkStartNotify()) {
                final int generation = this.getCrawlerGeneration(true);
                try {
                    if (insideCrawlerPlugin()) {
                        java.util.List<CrawledLink> links = _crawl(text, url, allowDeep);
                        crawl(links);
                    } else {
                        if (checkStartNotify()) {
                            threadPool.execute(new LinkCrawlerRunnable(LinkCrawler.this, generation) {
                                @Override
                                public long getAverageRuntime() {
                                    final Long ret = getDefaultAverageRuntime();
                                    if (ret != null) {
                                        return ret;
                                    }
                                    return super.getAverageRuntime();
                                }

                                @Override
                                void crawling() {
                                    java.util.List<CrawledLink> links = _crawl(text, url, allowDeep);
                                    crawl(links);
                                }
                            });
                        }
                    }
                } finally {
                    checkFinishNotify();
                }
            }
        }
    }

    private java.util.List<CrawledLink> _crawl(String text, String url, boolean allowDeep) {
        String[] possibleLinks = HTMLParser.getHttpLinks(text, url);
        if (possibleLinks != null && possibleLinks.length > 0) {
            final java.util.List<CrawledLink> possibleCryptedLinks = new ArrayList<CrawledLink>(possibleLinks.length);
            for (String possibleLink : possibleLinks) {
                CrawledLink link = crawledLinkFactorybyURL(possibleLink);
                link.setCrawlDeep(allowDeep);
                possibleCryptedLinks.add(link);
            }
            return possibleCryptedLinks;
        }
        return null;
    }

    public void crawl(final java.util.List<CrawledLink> possibleCryptedLinks) {
        if (possibleCryptedLinks != null && possibleCryptedLinks.size() > 0) {
            if (checkStartNotify()) {
                try {
                    final int generation = this.getCrawlerGeneration(true);
                    if (insideCrawlerPlugin()) {
                        /*
                         * direct decrypt this link because we are already inside a LinkCrawlerThread and this avoids deadlocks on plugin
                         * waiting for linkcrawler results
                         */
                        distribute(possibleCryptedLinks);
                    } else {
                        /*
                         * enqueue this cryptedLink for decrypting
                         */
                        if (checkStartNotify()) {
                            threadPool.execute(new LinkCrawlerRunnable(LinkCrawler.this, generation) {
                                @Override
                                public long getAverageRuntime() {
                                    final Long ret = getDefaultAverageRuntime();
                                    if (ret != null) {
                                        return ret;
                                    }
                                    return super.getAverageRuntime();
                                }

                                @Override
                                void crawling() {
                                    distribute(possibleCryptedLinks);
                                }
                            });
                        }

                    }
                } finally {
                    checkFinishNotify();
                }
            }
        }
    }

    public static boolean insideCrawlerPlugin() {
        if (Thread.currentThread() instanceof LinkCrawlerThread) {
            final Object owner = ((LinkCrawlerThread) Thread.currentThread()).getCurrentOwner();
            if (owner != null && owner instanceof PluginForDecrypt) {
                return true;
            }
        }
        return false;
    }

    public static boolean insideHosterPlugin() {
        if (Thread.currentThread() instanceof LinkCrawlerThread) {
            final Object owner = ((LinkCrawlerThread) Thread.currentThread()).getCurrentOwner();
            if (owner != null && owner instanceof PluginForHost) {
                return true;
            }
        }
        return false;
    }

    /*
     * check if all known crawlers are done and notify all waiting listener + cleanup DuplicateFinder
     */
    protected void checkFinishNotify() {
        if (crawler.decrementAndGet() == 0) {
            /* this LinkCrawler instance stopped, notify static counter */
            CRAWLER.decrementAndGet();
            synchronized (this) {
                this.notifyAll();
            }
            /*
             * all tasks are done , we can now cleanup our duplicateFinder
             */
            duplicateFinderContainer.clear();
            duplicateFinderCrawler.clear();
            duplicateFinderFinal.clear();
            duplicateFinderDeep.clear();
            EVENTSENDER.fireEvent(new LinkCrawlerEvent(this, LinkCrawlerEvent.Type.STOPPED));
            crawlerStopped();
        }
    }

    protected void crawlerStopped() {
    }

    protected void crawlerStarted() {
    }

    private boolean checkStartNotify() {
        if (allowCrawling.get()) {
            if (crawler.getAndIncrement() == 0) {
                CRAWLER.incrementAndGet();
                EVENTSENDER.fireEvent(new LinkCrawlerEvent(this, LinkCrawlerEvent.Type.STARTED));
                crawlerStarted();
            }
            return true;
        }
        return false;
    }

    protected void crawlDeeper(final CrawledLink source) {
        source.setCustomCrawledLinkModifier(null);
        final String[] sourceURLs = getAndClearSourceURLs(source);
        source.setBrokenCrawlerHandler(null);
        if (source == null || source.getURL() == null || duplicateFinderDeep.putIfAbsent(source.getURL(), this) != null || this.isCrawledLinkFiltered(source)) {
            return;
        }
        if (checkStartNotify()) {
            try {
                Browser br = null;
                try {
                    java.util.List<CrawledLink> possibleCryptedLinks = null;
                    new URL(source.getURL());
                    processedLinksCounter.incrementAndGet();
                    br = new Browser();
                    String url = source.getURL();
                    br.openGetConnection(url);
                    HashSet<String> loopAvoid = new HashSet<String>();
                    loopAvoid.add(url);
                    for (int i = 0; i < 10; i++) {
                        if (br.getRedirectLocation() != null) {
                            try {
                                br.getHttpConnection().disconnect();
                            } catch (Throwable e) {
                            }
                            url = br.getRedirectLocation();
                            if (loopAvoid.add(url) == false) {
                                break;
                            }
                            br.openGetConnection(url);
                        } else {
                            break;
                        }
                    }
                    int limit = Math.max(1 * 1024 * 1024, JsonConfig.create(LinkCrawlerConfig.class).getDeepDecryptLoadLimit());
                    if (br.getRedirectLocation() == null && (br.getHttpConnection().isContentDisposition() || ((br.getHttpConnection().getContentType() != null && !br.getHttpConnection().getContentType().contains("text")))) || br.getHttpConnection().getCompleteContentLength() > limit) {
                        try {
                            br.getHttpConnection().disconnect();
                        } catch (Throwable e) {
                        }
                        /*
                         * downloadable content, we use directhttp and distribute the url
                         */
                        possibleCryptedLinks = _crawl("directhttp://" + url, null, false);
                        if (possibleCryptedLinks != null && possibleCryptedLinks.size() >= 0) {
                            for (final CrawledLink possibleCryptedLink : possibleCryptedLinks) {
                                forwardCrawledLinkInfos(source, possibleCryptedLink, null, sourceURLs);
                            }
                            crawl(possibleCryptedLinks);
                        }
                    } else {
                        /* try to load the webpage and find links on it */
                        br.setLoadLimit(limit);
                        br.followConnection();
                        // We need browser currentURL and not sourceURL, because of possible redirects will change domain and or relative
                        // path.
                        String baseUrl = new Regex(br.getURL(), "(https?://.*?)(\\?|$)").getMatch(0);
                        final String finalBaseUrl = baseUrl;
                        final String browserContent = br.toString();
                        possibleCryptedLinks = _crawl(url, null, false);
                        if (possibleCryptedLinks != null) {
                            for (final CrawledLink possibleCryptedLink : possibleCryptedLinks) {
                                forwardCrawledLinkInfos(source, possibleCryptedLink, null, sourceURLs);
                            }
                            if (possibleCryptedLinks.size() == 1) {
                                /* first check if the url itself can be handled */
                                CrawledLink link = possibleCryptedLinks.get(0);
                                link.setUnknownHandler(new UnknownCrawledLinkHandler() {

                                    @Override
                                    public void unhandledCrawledLink(CrawledLink link, LinkCrawler lc) {
                                        /* unhandled url, lets parse the content on it */
                                        List<CrawledLink> possibleCryptedLinks2 = lc._crawl(browserContent, finalBaseUrl, false);
                                        if (possibleCryptedLinks2 != null && possibleCryptedLinks2.size() > 0) {
                                            for (final CrawledLink possibleCryptedLink : possibleCryptedLinks2) {
                                                forwardCrawledLinkInfos(source, possibleCryptedLink, null, sourceURLs);
                                            }
                                            lc.crawl(possibleCryptedLinks2);
                                        }
                                    }
                                });
                            }
                            crawl(possibleCryptedLinks);
                        }
                    }
                } catch (Throwable e) {
                    e.printStackTrace();
                } finally {
                    try {
                        br.getHttpConnection().disconnect();
                    } catch (Throwable e) {
                    }
                }
            } finally {
                checkFinishNotify();
            }
        }
    }

    protected boolean distributeCrawledLink(CrawledLink crawledLink) {
        return crawledLink != null && crawledLink.gethPlugin() == null;
    }

    protected Boolean distributePluginForHost(final LazyHostPlugin pluginForHost, final int generation, final String url, final CrawledLink link) {
        if (pluginForHost.canHandle(url)) {
            if (!isBlacklisted(pluginForHost)) {
                if (insideCrawlerPlugin()) {
                    if (generation != this.getCrawlerGeneration(false) || !isCrawlingAllowed()) {
                        /* LinkCrawler got aborted! */
                        return false;
                    }
                    processHostPlugin(pluginForHost, link);
                } else {
                    if (checkStartNotify()) {
                        threadPool.execute(new LinkCrawlerRunnable(LinkCrawler.this, generation) {
                            @Override
                            public long getAverageRuntime() {
                                final Long ret = getDefaultAverageRuntime();
                                if (ret != null) {
                                    return ret;
                                }
                                return pluginForHost.getAverageParseRuntime();
                            }

                            @Override
                            void crawling() {
                                processHostPlugin(pluginForHost, link);
                            }
                        });
                    } else {
                        /* LinkCrawler got aborted! */
                        return false;
                    }
                }
            }
            return true;
        }
        return null;
    }

    protected void distribute(java.util.List<CrawledLink> possibleCryptedLinks) {
        if (possibleCryptedLinks == null || possibleCryptedLinks.size() == 0) {
            return;
        }
        if (checkStartNotify()) {
            try {
                final int generation = this.getCrawlerGeneration(true);
                mainloop: for (final CrawledLink possibleCryptedLink : possibleCryptedLinks) {
                    if (generation != this.getCrawlerGeneration(false) || !isCrawlingAllowed()) {
                        /* LinkCrawler got aborted! */
                        return;
                    }
                    mainloopretry: while (true) {
                        UnknownCrawledLinkHandler unnknownHandler = possibleCryptedLink.getUnknownHandler();
                        possibleCryptedLink.setUnknownHandler(null);
                        if (!distributeCrawledLink(possibleCryptedLink)) {
                            // direct forward, if we already have a final link.
                            this.handleFinalCrawledLink(possibleCryptedLink);
                            continue mainloop;
                        }
                        String url = possibleCryptedLink.getURL();
                        if (url == null) {
                            /* WTF, no URL?! let's continue */
                            continue mainloop;
                        }
                        if (!url.startsWith("directhttp")) {
                            /*
                             * first we will walk through all available container plugins
                             */
                            for (final PluginsC pCon : ContainerPluginController.getInstance().list()) {
                                if (pCon.canHandle(url)) {
                                    try {
                                        final java.util.List<CrawledLink> allPossibleCryptedLinks = getCrawlableLinks(pCon.getSupportedLinks(), possibleCryptedLink, new CrawledLinkModifier() {
                                            /*
                                             * set new LinkModifier, hides the url if needed
                                             */
                                            public void modifyCrawledLink(CrawledLink link) {
                                                if (pCon.hideLinks()) {
                                                    /* we hide the links */
                                                    DownloadLink dl = link.getDownloadLink();
                                                    if (dl != null) {
                                                        dl.setUrlProtection(UrlProtection.PROTECTED_CONTAINER);

                                                    }
                                                }
                                            }
                                        });
                                        if (allPossibleCryptedLinks != null) {
                                            if (insideCrawlerPlugin()) {
                                                /*
                                                 * direct decrypt this link because we are already inside a LinkCrawlerThread and this
                                                 * avoids deadlocks on plugin waiting for linkcrawler results
                                                 */
                                                for (final CrawledLink decryptThis : allPossibleCryptedLinks) {
                                                    if (generation != this.getCrawlerGeneration(false) || !isCrawlingAllowed()) {
                                                        /* LinkCrawler got aborted! */
                                                        return;
                                                    }
                                                    container(pCon, decryptThis);
                                                }
                                            } else {
                                                /*
                                                 * enqueue these cryptedLinks for decrypting
                                                 */
                                                for (final CrawledLink decryptThis : allPossibleCryptedLinks) {
                                                    if (checkStartNotify()) {
                                                        threadPool.execute(new LinkCrawlerRunnable(LinkCrawler.this, generation) {
                                                            @Override
                                                            public long getAverageRuntime() {
                                                                final Long ret = getDefaultAverageRuntime();
                                                                if (ret != null) {
                                                                    return ret;
                                                                }
                                                                return super.getAverageRuntime();
                                                            }

                                                            @Override
                                                            void crawling() {
                                                                container(pCon, decryptThis);
                                                            }
                                                        });
                                                    } else {
                                                        return;
                                                    }
                                                }
                                            }
                                        }
                                    } catch (Throwable e) {
                                        LogController.CL().log(e);
                                    }
                                    continue mainloop;
                                }
                            }
                            /*
                             * first we will walk through all available decrypter plugins
                             */
                            for (final LazyCrawlerPlugin pDecrypt : getCrawlerPlugins()) {
                                if (pDecrypt.canHandle(url)) {
                                    if (!isBlacklisted(pDecrypt)) {
                                        try {
                                            final java.util.List<CrawledLink> allPossibleCryptedLinks = getCrawlableLinks(pDecrypt.getPattern(), possibleCryptedLink, null);
                                            if (allPossibleCryptedLinks != null) {
                                                if (insideCrawlerPlugin()) {
                                                    /*
                                                     * direct decrypt this link because we are already inside a LinkCrawlerThread and this
                                                     * avoids deadlocks on plugin waiting for linkcrawler results
                                                     */
                                                    for (final CrawledLink decryptThis : allPossibleCryptedLinks) {
                                                        if (generation != this.getCrawlerGeneration(false) || !isCrawlingAllowed()) {
                                                            /* LinkCrawler got aborted! */
                                                            return;
                                                        }
                                                        crawl(pDecrypt, decryptThis);
                                                    }
                                                } else {
                                                    /*
                                                     * enqueue these cryptedLinks for decrypting
                                                     */
                                                    for (final CrawledLink decryptThis : allPossibleCryptedLinks) {
                                                        if (checkStartNotify()) {
                                                            threadPool.execute(new LinkCrawlerRunnable(LinkCrawler.this, generation) {

                                                                public long getAverageRuntime() {
                                                                    final Long ret = getDefaultAverageRuntime();
                                                                    if (ret != null) {
                                                                        return ret;
                                                                    }
                                                                    return pDecrypt.getAverageCrawlRuntime();
                                                                }

                                                                @Override
                                                                protected Object sequentialLockingObject() {
                                                                    return pDecrypt.getDisplayName();
                                                                }

                                                                @Override
                                                                protected int maxConcurrency() {
                                                                    return pDecrypt.getMaxConcurrentInstances();
                                                                }

                                                                @Override
                                                                void crawling() {
                                                                    crawl(pDecrypt, decryptThis);
                                                                }
                                                            });
                                                        } else {
                                                            return;
                                                        }
                                                    }
                                                }
                                            }
                                        } catch (Throwable e) {
                                            LogController.CL().log(e);
                                        }
                                    }
                                    continue mainloop;
                                }
                            }
                        }
                        /* now we will walk through all available hoster plugins */
                        for (final LazyHostPlugin pHost : getHosterPlugins()) {
                            if (!isDirectHttpEnabled() && (pHost.getDisplayName().equals(DIRECT_HTTP) || pHost.getDisplayName().equals(HTTP_LINKS))) {
                                continue;
                            }
                            final Boolean ret = distributePluginForHost(pHost, generation, url, possibleCryptedLink);
                            if (Boolean.FALSE.equals(ret)) {
                                return;
                            } else if (Boolean.TRUE.equals(ret)) {
                                continue mainloop;
                            }
                        }
                        if (unnknownHandler != null) {
                            /*
                             * CrawledLink is unhandled till now , but has an UnknownHandler set, lets call it, maybe it makes the Link
                             * handable by a Plugin
                             */
                            try {
                                unnknownHandler.unhandledCrawledLink(possibleCryptedLink, this);
                            } catch (final Throwable e) {
                                LogController.CL().log(e);
                            }
                            /* lets retry this crawledLink */
                            continue mainloopretry;
                        }
                        /* now we will check for normal http links */
                        if (!url.startsWith("ftp") && directHTTP != null && isDirectHttpEnabled() && !isBlacklisted(directHTTP)) {
                            url = url.replaceFirst("http://", "httpviajd://");
                            url = url.replaceFirst("https://", "httpsviajd://");
                            /* create new CrawledLink that holds the modified CrawledLink */
                            final CrawledLinkModifier parentLinkModifier = possibleCryptedLink.getCustomCrawledLinkModifier();
                            possibleCryptedLink.setCustomCrawledLinkModifier(null);
                            final String[] sourceURLs = getAndClearSourceURLs(possibleCryptedLink);
                            DownloadLink dl = possibleCryptedLink.getDownloadLink();
                            final CrawledLink modifiedPossibleCryptedLink;
                            if (dl != null) {
                                modifiedPossibleCryptedLink = new CrawledLink(new DownloadLink(dl.getDefaultPlugin(), dl.getView().getDisplayName(), dl.getHost(), url, dl.isEnabled()));
                                /* forward downloadLink infos from source to dest */
                                java.util.List<DownloadLink> dlLinks = new ArrayList<DownloadLink>();
                                dlLinks.add(modifiedPossibleCryptedLink.getDownloadLink());
                                forwardDownloadLinkInfos(dl, dlLinks);
                            } else {
                                modifiedPossibleCryptedLink = new CrawledLink(url);
                            }
                            forwardCrawledLinkInfos(possibleCryptedLink, modifiedPossibleCryptedLink, parentLinkModifier, sourceURLs);
                            final Boolean ret = distributePluginForHost(directHTTP, generation, url, modifiedPossibleCryptedLink);
                            if (Boolean.FALSE.equals(ret)) {
                                return;
                            } else if (Boolean.TRUE.equals(ret)) {
                                continue mainloop;
                            }
                        }
                        /* now we will check for generic ftp links */
                        if (url.startsWith("ftp") && ftp != null && !isBlacklisted(ftp)) {
                            final Boolean ret = distributePluginForHost(ftp, generation, url, possibleCryptedLink);
                            if (Boolean.FALSE.equals(ret)) {
                                return;
                            } else if (Boolean.TRUE.equals(ret)) {
                                continue mainloop;
                            }
                            if (Boolean.FALSE.equals(ret)) {
                                return;
                            } else if (Boolean.TRUE.equals(ret)) {
                                continue mainloop;
                            }
                        }
                        if (possibleCryptedLink.isCrawlDeep()) {
                            /* the link is allowed to crawlDeep */
                            if (insideCrawlerPlugin()) {
                                if (generation != this.getCrawlerGeneration(false) || !isCrawlingAllowed()) {
                                    /* LinkCrawler got aborted! */
                                    return;
                                }
                                crawlDeeper(possibleCryptedLink);
                            } else {
                                if (checkStartNotify()) {
                                    threadPool.execute(new LinkCrawlerRunnable(LinkCrawler.this, generation) {
                                        @Override
                                        public long getAverageRuntime() {
                                            final Long ret = getDefaultAverageRuntime();
                                            if (ret != null) {
                                                return ret;
                                            }
                                            return super.getAverageRuntime();
                                        }

                                        @Override
                                        void crawling() {
                                            crawlDeeper(possibleCryptedLink);
                                        }
                                    });
                                } else {
                                    return;
                                }
                            }
                            continue mainloop;
                        }
                        /* break for mainloopretry */
                        break;
                    }
                    handleUnhandledCryptedLink(possibleCryptedLink);
                }
            } finally {
                checkFinishNotify();
            }
        }
    }

    protected List<LazyCrawlerPlugin> getCrawlerPlugins() {
        if (parentCrawler != null) {
            final List<LazyCrawlerPlugin> ret = parentCrawler.getCrawlerPlugins();
            if (ret != null) {
                return ret;
            }
        }
        if (cHosts == null) {
            cHosts = CrawlerPluginController.getInstance().list();
        }
        /* sort cHosts according to their usage */
        final ArrayList<LazyCrawlerPlugin> ret = new ArrayList<LazyCrawlerPlugin>(cHosts);
        try {
            Collections.sort(ret, new Comparator<LazyCrawlerPlugin>() {

                @Override
                public final int compare(LazyCrawlerPlugin o1, LazyCrawlerPlugin o2) {
                    final LazyPluginClass l1 = o1.getLazyPluginClass();
                    final LazyPluginClass l2 = o2.getLazyPluginClass();
                    if (l1.getInterfaceVersion() == l2.getInterfaceVersion()) {
                        return 0;
                    }
                    if (l1.getInterfaceVersion() > l2.getInterfaceVersion()) {
                        return -1;
                    }
                    return 1;
                }

            });
            Collections.sort(ret, new Comparator<LazyCrawlerPlugin>() {
                public final int compare(long x, long y) {
                    return (x < y) ? 1 : ((x == y) ? 0 : -1);
                }

                @Override
                public final int compare(LazyCrawlerPlugin o1, LazyCrawlerPlugin o2) {
                    return compare(o1.getPluginUsage(), o2.getPluginUsage());
                }

            });
        } catch (final Throwable e) {
            LogController.CL(true).log(e);
        }
        return ret;
    }

    protected List<LazyHostPlugin> getHosterPlugins() {
        /* sort pHosts according to their usage */
        final ArrayList<LazyHostPlugin> ret = new ArrayList<LazyHostPlugin>(pHosts);
        try {
            Collections.sort(ret, new Comparator<LazyHostPlugin>() {

                public final int compare(long x, long y) {
                    return (x < y) ? 1 : ((x == y) ? 0 : -1);
                }

                @Override
                public final int compare(LazyHostPlugin o1, LazyHostPlugin o2) {
                    return compare(o1.getPluginUsage(), o2.getPluginUsage());
                }

            });
        } catch (final Throwable e) {
            LogController.CL(true).log(e);
        }
        return ret;
    }

    public boolean isDirectHttpEnabled() {
        return directHttpEnabled;
    }

    public java.util.List<CrawledLink> getCrawlableLinks(Pattern pattern, CrawledLink possibleCryptedLink, CrawledLinkModifier modifier) {
        /*
         * we dont need memory optimization here as downloadlink, crypted link itself take care of this
         */
        final String[] hits = new Regex(possibleCryptedLink.getURL(), pattern).getColumn(-1);
        if (hits != null && hits.length > 0) {
            final ArrayList<CrawledLink> chits = new ArrayList<CrawledLink>(hits.length);
            for (String hit : hits) {
                String file = hit;
                file = file.trim();
                /* cut of any unwanted chars */
                while (file.length() > 0 && file.charAt(0) == '"') {
                    file = file.substring(1);
                }
                while (file.length() > 0 && file.charAt(file.length() - 1) == '"') {
                    file = file.substring(0, file.length() - 1);
                }
                file = file.trim();
                chits.add(new CrawledLink(new CryptedLink(file)));
            }
            for (CrawledLink decryptThis : chits) {
                /*
                 * forward important data to new ones
                 */
                forwardCrawledLinkInfos(possibleCryptedLink, decryptThis, modifier, null);
                if (possibleCryptedLink.getCryptedLink() != null) {
                    /*
                     * source contains CryptedLink, so lets forward important infos
                     */
                    Map<String, Object> props = possibleCryptedLink.getCryptedLink().getProperties();
                    if (props != null && !props.isEmpty()) {
                        decryptThis.getCryptedLink().setProperties(props);
                    }
                }
                final String pw;
                if (possibleCryptedLink.getCryptedLink() != null && possibleCryptedLink.getCryptedLink().getDecrypterPassword() != null) {
                    pw = possibleCryptedLink.getCryptedLink().getDecrypterPassword();
                } else if (LinkCrawler.this instanceof JobLinkCrawler && ((JobLinkCrawler) LinkCrawler.this).getJob().getCrawlerPassword() != null) {
                    pw = ((JobLinkCrawler) LinkCrawler.this).getJob().getCrawlerPassword();
                } else if (possibleCryptedLink.getSourceLink() != null && possibleCryptedLink.getSourceLink().getDownloadLink() != null) {
                    pw = possibleCryptedLink.getSourceLink().getDownloadLink().getDownloadPassword();
                } else {
                    pw = null;
                }
                decryptThis.getCryptedLink().setDecrypterPassword(pw);
            }
            return chits;
        }
        return null;
    }

    protected void processHostPlugin(LazyHostPlugin pHost, CrawledLink possibleCryptedLink) {
        final CrawledLinkModifier parentLinkModifier = possibleCryptedLink.getCustomCrawledLinkModifier();
        possibleCryptedLink.setCustomCrawledLinkModifier(null);
        final String[] sourceURLs = getAndClearSourceURLs(possibleCryptedLink);
        possibleCryptedLink.setBrokenCrawlerHandler(null);
        if (pHost == null || possibleCryptedLink.getURL() == null || this.isCrawledLinkFiltered(possibleCryptedLink)) {
            return;
        }
        if (checkStartNotify()) {
            try {
                PluginForHost wplg = null;
                /*
                 * use a new PluginClassLoader here
                 */
                wplg = pHost.newInstance(getPluginClassLoaderChild());
                if (wplg != null) {
                    /* now we run the plugin and let it find some links */
                    LinkCrawlerThread lct = null;
                    if (Thread.currentThread() instanceof LinkCrawlerThread) {
                        lct = (LinkCrawlerThread) Thread.currentThread();
                    }
                    Object owner = null;
                    LinkCrawler previousCrawler = null;
                    boolean oldDebug = false;
                    boolean oldVerbose = false;
                    Logger oldLogger = null;
                    try {
                        LogSource logger = LogController.getFastPluginLogger(wplg.getHost());
                        logger.info("Processing: " + possibleCryptedLink.getURL());
                        if (lct != null) {
                            /* mark thread to be used by crawler plugin */
                            owner = lct.getCurrentOwner();
                            lct.setCurrentOwner(wplg);
                            previousCrawler = lct.getCurrentLinkCrawler();
                            lct.setCurrentLinkCrawler(this);
                            /* save old logger/states */
                            oldLogger = lct.getLogger();
                            oldDebug = lct.isDebug();
                            oldVerbose = lct.isVerbose();
                            /* set new logger and set verbose/debug true */
                            lct.setLogger(logger);
                            lct.setVerbose(true);
                            lct.setDebug(true);
                        }
                        wplg.setBrowser(new Browser());
                        wplg.setLogger(logger);
                        String url = possibleCryptedLink.getURL();
                        FilePackage sourcePackage = null;
                        if (possibleCryptedLink.getDownloadLink() != null) {
                            sourcePackage = possibleCryptedLink.getDownloadLink().getFilePackage();
                            if (FilePackage.isDefaultFilePackage(sourcePackage)) {
                                /* we don't want the various filePackage getting used */
                                sourcePackage = null;
                            }
                        }
                        long startTime = System.currentTimeMillis();
                        java.util.List<DownloadLink> hosterLinks = null;
                        try {
                            hosterLinks = wplg.getDownloadLinks(url, sourcePackage);
                            if (hosterLinks != null) {
                                final UrlProtection protection = wplg.getUrlProtection(hosterLinks);
                                if (protection != null && protection != UrlProtection.UNSET) {
                                    for (DownloadLink dl : hosterLinks) {
                                        if (dl.getUrlProtection() == UrlProtection.UNSET) {
                                            dl.setUrlProtection(protection);
                                        }
                                    }
                                }
                            }
                            /* in case the function returned without exceptions, we can clear log */
                            logger.clear();
                        } finally {
                            long endTime = System.currentTimeMillis() - startTime;
                            wplg.getLazyP().updateParseRuntime(endTime);
                            /* close the logger */
                            logger.close();
                        }
                        if (hosterLinks != null) {
                            forwardDownloadLinkInfos(possibleCryptedLink.getDownloadLink(), hosterLinks);
                            for (final DownloadLink hosterLink : hosterLinks) {
                                final CrawledLink link = new CrawledLink(hosterLink);
                                /*
                                 * forward important data to new ones
                                 */
                                forwardCrawledLinkInfos(possibleCryptedLink, link, parentLinkModifier, sourceURLs);
                                handleFinalCrawledLink(link);
                            }
                        }
                    } finally {
                        if (lct != null) {
                            /* reset thread to last known used state */
                            lct.setCurrentOwner(owner);
                            lct.setCurrentLinkCrawler(previousCrawler);
                            lct.setLogger(oldLogger);
                            lct.setVerbose(oldVerbose);
                            lct.setDebug(oldDebug);
                        }
                    }
                } else {
                    LogController.CL().info("Hoster Plugin not available:" + pHost.getDisplayName());
                }
            } catch (Throwable e) {
                LogController.CL().log(e);
            } finally {
                /* restore old ClassLoader for current Thread */
                checkFinishNotify();
            }
        }
    }

    private String[] getAndClearSourceURLs(CrawledLink link) {
        ArrayList<String> sources = new ArrayList<String>();
        CrawledLink source = link;

        while (source != null) {
            if (sources.size() == 0 || !StringUtils.equals(source.getURL(), sources.get(sources.size() - 1))) {
                sources.add(source.getURL());
            }
            source = source.getSourceLink();
        }
        link.setSourceUrls(null);
        if (link.getSourceJob() != null) {
            String cust = link.getSourceJob().getCustomSourceUrl();
            if (cust != null) {
                sources.add(cust);
            }
        } else if (this instanceof JobLinkCrawler) {
            String cust = ((JobLinkCrawler) this).getJob().getCustomSourceUrl();
            if (cust != null) {
                sources.add(cust);
            }
        }
        return sources.toArray(new String[] {});
    }

    public static String getUnsafeName(String unsafeName, String currentName) {
        if (unsafeName != null) {
            String extension = Files.getExtension(unsafeName);
            if (extension == null && unsafeName.indexOf('.') < 0) {
                String unsafeSourceModified = null;
                if (unsafeName.indexOf('_') > 0) {
                    unsafeSourceModified = unsafeName.replaceAll("_", ".");
                    extension = Files.getExtension(unsafeSourceModified);
                }
                if (extension == null && unsafeName.indexOf('-') > 0) {
                    unsafeSourceModified = unsafeName.replaceAll("-", ".");
                    extension = Files.getExtension(unsafeSourceModified);
                }
                if (extension != null) {
                    unsafeName = unsafeSourceModified;
                }
            }
            if (extension != null && !StringUtils.equals(currentName, unsafeName)) {
                return unsafeName;
            }
        }
        return null;
    }

    private void forwardCrawledLinkInfos(CrawledLink source, CrawledLink dest, final CrawledLinkModifier linkModifier, final String sourceURLs[]) {
        if (source == null || dest == null) {
            return;
        }
        dest.setSourceLink(source);
        dest.setOrigin(source.getOrigin());
        dest.setSourceUrls(sourceURLs);
        dest.setMatchingFilter(source.getMatchingFilter());
        final DownloadLink dlLink = dest.getDownloadLink();
        if (dlLink != null && !dlLink.isNameSet()) {
            final String name = getUnsafeName(source.getName(), dlLink.getName());
            if (name != null) {
                dlLink.setName(name);
            }
        }
        final CrawledLinkModifier childCustomModifier = dest.getCustomCrawledLinkModifier();
        if (childCustomModifier == null) {
            dest.setCustomCrawledLinkModifier(linkModifier);
        } else if (linkModifier != null) {
            dest.setCustomCrawledLinkModifier(new CrawledLinkModifier() {

                @Override
                public void modifyCrawledLink(CrawledLink link) {
                    if (linkModifier != null) {
                        linkModifier.modifyCrawledLink(link);
                    }
                    childCustomModifier.modifyCrawledLink(link);
                }
            });
        }
        if (source.getDownloadLink() != null && dest.getDownloadLink() != null && dest.getDownloadLink().getUrlProtection() == UrlProtection.UNSET) {
            dest.getDownloadLink().setUrlProtection(source.getDownloadLink().getUrlProtection());
        }
        // if we decrypted a dlc,source.getDesiredPackageInfo() is null, and dest might already have package infos from the container.
        // maybe it would be even better to merge the packageinfos
        // However. if there are crypted links in the container, it may be up to the decrypterplugin to decide
        // example: share-links.biz uses CNL to post links to localhost. the dlc origin get's lost on such a way
        if (source.getDesiredPackageInfo() != null) {
            dest.setDesiredPackageInfo(source.getDesiredPackageInfo());
        }

        ArchiveInfo d = null;
        if (dest.hasArchiveInfo()) {
            d = dest.getArchiveInfo();
        }
        if (source.hasArchiveInfo()) {
            if (d == null) {
                dest.setArchiveInfo(new ArchiveInfo().migrate(source.getArchiveInfo()));
            } else {
                d.migrate(source.getArchiveInfo());
            }
        }
        convertFilePackageInfos(dest);
        permanentOffline(dest);
    }

    private PackageInfo convertFilePackageInfos(CrawledLink link) {
        if (link.getDownloadLink() != null) {
            final FilePackage fp = link.getDownloadLink().getFilePackage();
            if (!FilePackage.isDefaultFilePackage(fp)) {
                PackageInfo fpi = link.getDesiredPackageInfo();
                if (fpi == null) {
                    fpi = new PackageInfo();
                }
                FilePackage dp = link.getDownloadLink().getFilePackage();
                if (dp.getDownloadDirectory() != null && !dp.getDownloadDirectory().equals(defaultDownloadFolder)) {
                    // do not set downloadfolder if it is the defaultfolder
                    fpi.setDestinationFolder(dp.getDownloadDirectory());
                }

                if (Boolean.FALSE.equals(dp.getBooleanProperty(PACKAGE_CLEANUP_NAME, true))) {
                    fpi.setName(dp.getName());
                } else {
                    fpi.setName(LinknameCleaner.cleanFileName(dp.getName(), false, true, LinknameCleaner.EXTENSION_SETTINGS.REMOVE_KNOWN, true));
                }

                if (dp.hasProperty(PACKAGE_ALLOW_MERGE)) {
                    if (Boolean.FALSE.equals(dp.getBooleanProperty(PACKAGE_ALLOW_MERGE, false))) {
                        fpi.setUniqueId(dp.getUniqueID());
                    } else {
                        fpi.setUniqueId(null);
                    }
                } else {
                    fpi.setUniqueId(dp.getUniqueID());
                }
                if (dp.hasProperty(PACKAGE_IGNORE_VARIOUS)) {
                    if (Boolean.TRUE.equals(dp.getBooleanProperty(PACKAGE_IGNORE_VARIOUS, false))) {
                        fpi.setIgnoreVarious(true);
                    } else {
                        fpi.setIgnoreVarious(false);
                    }
                }
                link.setDesiredPackageInfo(fpi);
                return fpi;
            }
        }
        return null;
    }

    private void permanentOffline(CrawledLink link) {
        DownloadLink dl = link.getDownloadLink();
        try {
            if (dl != null && dl.getDefaultPlugin().getLazyP().getClassName().equals("jd.plugins.hoster.Offline")) {
                PackageInfo dpi = link.getDesiredPackageInfo();
                if (dpi == null) {
                    dpi = new PackageInfo();
                    link.setDesiredPackageInfo(dpi);
                }
                dpi.setUniqueId(PERMANENT_OFFLINE_ID);
            }
        } catch (final Throwable e) {
        }

    }

    protected void forwardDownloadLinkInfos(DownloadLink source, List<DownloadLink> dests) {
        if (source == null || dests == null || dests.size() == 0) {
            return;
        }
        // source.getFilePackage().remove(source);
        for (DownloadLink dl : dests) {
            /* create copy of ArrayList */
            List<String> srcPWs = source.getSourcePluginPasswordList();
            if (srcPWs != null && srcPWs.size() > 0) {
                dl.setSourcePluginPasswordList(new ArrayList<String>(srcPWs));
            }
            if (source.getComment() != null) {
                dl.setComment(source.getComment());
            }
            if (source.isNameSet()) {
                dl.setName(source.getName());
            } else {
                final String name = getUnsafeName(source.getName(), dl.getName());
                if (name != null) {
                    dl.setName(name);
                }
            }
            if (source.getForcedFileName() != null) {
                dl.setForcedFileName(source.getForcedFileName());
            }
            if (source.getFinalFileName() != null) {
                dl.setFinalFileName(source.getFinalFileName());
            }

            if (source.getAvailableStatus() != dl.getAvailableStatus()) {
                dl.setAvailableStatus(source.getAvailableStatus());
            }
            Map<String, Object> props = source.getProperties();
            if (props != null && !props.isEmpty()) {
                dl.setProperties(props);
            }
            dl.setDownloadSize(source.getView().getBytesTotal());
            if (dl.getUrlProtection() == UrlProtection.UNSET && source.getUrlProtection() != UrlProtection.UNSET) {
                dl.setUrlProtection(source.getUrlProtection());
            }
        }
    }

    public boolean isCrawlingAllowed() {
        return this.allowCrawling.get();
    }

    public void setCrawlingAllowed(boolean b) {
        this.allowCrawling.set(b);
    }

    public void stopCrawling() {
        crawlerGeneration.incrementAndGet();
    }

    public boolean waitForCrawling() {
        while (crawler.get() > 0) {
            synchronized (LinkCrawler.this) {
                if (crawler.get() > 0) {
                    try {
                        LinkCrawler.this.wait(1000);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
        }
        return crawler.get() == 0;
    }

    public boolean isRunning() {
        return crawler.get() > 0;
    }

    public static boolean isCrawling() {
        return CRAWLER.get() > 0;
    }

    protected void container(PluginsC oplg, final CrawledLink cryptedLink) {
        final CrawledLinkModifier parentLinkModifier = cryptedLink.getCustomCrawledLinkModifier();
        cryptedLink.setCustomCrawledLinkModifier(null);
        final String[] sourceURLs = getAndClearSourceURLs(cryptedLink);
        cryptedLink.setBrokenCrawlerHandler(null);
        if (oplg == null || cryptedLink.getURL() == null || duplicateFinderContainer.putIfAbsent(cryptedLink.getURL(), this) != null || this.isCrawledLinkFiltered(cryptedLink)) {
            return;
        }
        if (checkStartNotify()) {
            final int generation = this.getCrawlerGeneration(true);
            try {
                processedLinksCounter.incrementAndGet();
                /* set new PluginClassLoaderChild because ContainerPlugin maybe uses Hoster/Crawler */
                PluginsC plg = null;
                try {
                    plg = oplg.getClass().newInstance();
                } catch (final Throwable e) {
                    LogController.CL().log(e);
                    return;
                }
                /* now we run the plugin and let it find some links */
                LinkCrawlerThread lct = null;
                if (Thread.currentThread() instanceof LinkCrawlerThread) {
                    lct = (LinkCrawlerThread) Thread.currentThread();
                }
                Object owner = null;
                LinkCrawler previousCrawler = null;
                boolean oldDebug = false;
                boolean oldVerbose = false;
                Logger oldLogger = null;
                try {
                    LogSource logger = LogController.getFastPluginLogger(plg.getName());
                    if (lct != null) {
                        /* mark thread to be used by crawler plugin */
                        owner = lct.getCurrentOwner();
                        lct.setCurrentOwner(plg);
                        previousCrawler = lct.getCurrentLinkCrawler();
                        lct.setCurrentLinkCrawler(this);
                        /* save old logger/states */
                        oldLogger = lct.getLogger();
                        oldDebug = lct.isDebug();
                        oldVerbose = lct.isVerbose();
                        /* set new logger and set verbose/debug true */
                        lct.setLogger(logger);
                        lct.setVerbose(true);
                        lct.setDebug(true);
                    }
                    plg.setLogger(logger);
                    try {
                        final java.util.List<CrawledLink> decryptedPossibleLinks = plg.decryptContainer(cryptedLink);
                        /* in case the function returned without exceptions, we can clear log */
                        logger.clear();
                        if (decryptedPossibleLinks != null && decryptedPossibleLinks.size() > 0) {
                            /* we found some links, distribute them */
                            for (CrawledLink decryptedPossibleLink : decryptedPossibleLinks) {
                                forwardCrawledLinkInfos(cryptedLink, decryptedPossibleLink, parentLinkModifier, sourceURLs);
                            }
                            if (checkStartNotify()) {
                                /* enqueue distributing of the links */
                                threadPool.execute(new LinkCrawlerRunnable(LinkCrawler.this, generation) {
                                    @Override
                                    public long getAverageRuntime() {
                                        final Long ret = getDefaultAverageRuntime();
                                        if (ret != null) {
                                            return ret;
                                        }
                                        return super.getAverageRuntime();
                                    }

                                    @Override
                                    void crawling() {
                                        LinkCrawler.this.distribute(decryptedPossibleLinks);
                                    }
                                });
                            }
                        }
                    } finally {
                        /* close the logger */
                        logger.close();
                    }
                } finally {
                    if (lct != null) {
                        /* reset thread to last known used state */
                        lct.setCurrentOwner(owner);
                        lct.setCurrentLinkCrawler(previousCrawler);
                        lct.setLogger(oldLogger);
                        lct.setVerbose(oldVerbose);
                        lct.setDebug(oldDebug);
                    }
                }
            } finally {
                /* restore old ClassLoader for current Thread */
                checkFinishNotify();
            }
        }
    }

    protected void crawl(LazyCrawlerPlugin lazyC, final CrawledLink cryptedLink) {
        final CrawledLinkModifier parentLinkModifier = cryptedLink.getCustomCrawledLinkModifier();
        cryptedLink.setCustomCrawledLinkModifier(null);
        final String[] sourceURLs = getAndClearSourceURLs(cryptedLink);
        final BrokenCrawlerHandler brokenCrawler = cryptedLink.getBrokenCrawlerHandler();
        cryptedLink.setBrokenCrawlerHandler(null);
        if (lazyC == null || cryptedLink.getCryptedLink() == null || duplicateFinderCrawler.putIfAbsent(cryptedLink.getURL(), this) != null || this.isCrawledLinkFiltered(cryptedLink)) {
            return;
        }
        if (checkStartNotify()) {
            try {
                final int generation = this.getCrawlerGeneration(true);
                processedLinksCounter.incrementAndGet();
                final PluginForDecrypt wplg;
                /*
                 * we want a fresh pluginClassLoader here
                 */
                try {
                    wplg = lazyC.newInstance(getPluginClassLoaderChild());
                } catch (UpdateRequiredClassNotFoundException e1) {
                    LogController.CL().log(e1);
                    return;
                }
                wplg.setBrowser(new Browser());
                LogSource logger = null;
                Logger oldLogger = null;
                boolean oldVerbose = false;
                boolean oldDebug = false;
                logger = LogController.getFastPluginLogger(wplg.getHost());
                logger.info("Crawling: " + cryptedLink.getURL());
                wplg.setLogger(logger);
                /* now we run the plugin and let it find some links */
                LinkCrawlerThread lct = null;
                if (Thread.currentThread() instanceof LinkCrawlerThread) {
                    lct = (LinkCrawlerThread) Thread.currentThread();
                }
                Object owner = null;
                LinkCrawlerDistributer dist = null;
                DelayedRunnable distributeLinksDelayer = null;
                LinkCrawler previousCrawler = null;
                java.util.List<DownloadLink> decryptedPossibleLinks = null;
                try {
                    final java.util.List<CrawledLink> distributedLinks = new ArrayList<CrawledLink>();
                    final boolean useDelay = wplg.getDistributeDelayerMinimum() > 0;
                    int minimumDelay = Math.max(10, wplg.getDistributeDelayerMinimum());
                    int maximumDelay = wplg.getDistributeDelayerMaximum();
                    if (maximumDelay == 0) {
                        maximumDelay = -1;
                    }
                    distributeLinksDelayer = new DelayedRunnable(TIMINGQUEUE, minimumDelay, maximumDelay) {

                        @Override
                        public String getID() {
                            return "LinkCrawler";
                        }

                        @Override
                        public void delayedrun() {
                            /* we are now in IOEQ, thats why we create copy and then push work back into LinkCrawler */
                            java.util.List<CrawledLink> linksToDistribute = null;
                            synchronized (distributedLinks) {
                                if (distributedLinks.size() == 0) {
                                    return;
                                }
                                linksToDistribute = new ArrayList<CrawledLink>(distributedLinks);
                                distributedLinks.clear();
                            }
                            final java.util.List<CrawledLink> linksToDistributeFinal = linksToDistribute;
                            if (checkStartNotify()) {
                                /* enqueue distributing of the links */
                                threadPool.execute(new LinkCrawlerRunnable(LinkCrawler.this, generation) {
                                    @Override
                                    public long getAverageRuntime() {
                                        final Long ret = getDefaultAverageRuntime();
                                        if (ret != null) {
                                            return ret;
                                        }
                                        return super.getAverageRuntime();
                                    }

                                    @Override
                                    void crawling() {
                                        final java.util.List<CrawledLink> distributeThis = new ArrayList<CrawledLink>(linksToDistributeFinal);
                                        LinkCrawler.this.distribute(distributeThis);
                                    }
                                });
                            }
                        }
                    };
                    final DelayedRunnable distributeLinksDelayerFinal = distributeLinksDelayer;
                    /*
                     * set LinkCrawlerDistributer in case the plugin wants to add links in realtime
                     */
                    final CrawledLinkModifier lm = new CrawledLinkModifier() {
                        /*
                         * this modifier sets the BrowserURL if not set yet
                         */
                        public void modifyCrawledLink(CrawledLink link) {
                            if (parentLinkModifier != null) {
                                parentLinkModifier.modifyCrawledLink(link);
                            }
                        }

                    };
                    wplg.setDistributer(dist = new LinkCrawlerDistributer() {

                        final HashSet<DownloadLink> fastDuplicateDetector = new HashSet<DownloadLink>();
                        final AtomicInteger         distributed           = new AtomicInteger(0);
                        final HashSet<DownloadLink> distribute            = new HashSet<DownloadLink>();

                        public synchronized void distribute(DownloadLink... links) {
                            if (links == null || links.length == 0) {
                                return;
                            }

                            for (DownloadLink link : links) {
                                if (link.getPluginPatternMatcher() != null && !fastDuplicateDetector.contains(link)) {
                                    distribute.add(link);
                                }
                            }
                            if (wplg.getDistributer() != null && (distribute.size() + distributed.get()) <= 1) {
                                /**
                                 * crawler is still running, wait for finish or multiple distributed
                                 */
                                return;
                            }
                            final List<CrawledLink> possibleCryptedLinks = new ArrayList<CrawledLink>(distribute.size());
                            final boolean distributeMultipleLinks = (distribute.size() + distributed.get()) > 1;
                            for (DownloadLink link : distribute) {
                                if (link.getPluginPatternMatcher() != null && fastDuplicateDetector.add(link)) {
                                    distributed.incrementAndGet();
                                    if (link.getPluginPatternMatcher().contains("decrypted.com")) {
                                        /**
                                         * some plugins have same regex for hoster/decrypter, so they add decrypted.com at the end
                                         */
                                        if (distributeMultipleLinks) {
                                            if (link.getContainerUrl() == null) {
                                                link.setContainerUrl(cryptedLink.getCryptedLink().getCryptedUrl());
                                            }
                                        } else {
                                            if (link.getContentUrl() == null) {
                                                link.setContentUrl(cryptedLink.getCryptedLink().getCryptedUrl());
                                            }
                                        }
                                    }
                                    CrawledLink ret = new CrawledLink(link);
                                    possibleCryptedLinks.add(ret);
                                    forwardCrawledLinkInfos(cryptedLink, ret, lm, sourceURLs);
                                }
                            }
                            distribute.clear();
                            if (useDelay) {
                                /* we delay the distribute */
                                synchronized (distributedLinks) {
                                    /* synchronized adding */
                                    distributedLinks.addAll(possibleCryptedLinks);
                                }
                                /* restart delayer to distribute links */
                                distributeLinksDelayerFinal.run();
                            } else {
                                /* we do not delay the distribute */
                                if (checkStartNotify()) {
                                    /* enqueue distributing of the links */
                                    threadPool.execute(new LinkCrawlerRunnable(LinkCrawler.this, generation) {
                                        @Override
                                        public long getAverageRuntime() {
                                            final Long ret = getDefaultAverageRuntime();
                                            if (ret != null) {
                                                return ret;
                                            }
                                            return super.getAverageRuntime();
                                        }

                                        @Override
                                        void crawling() {
                                            LinkCrawler.this.distribute(possibleCryptedLinks);
                                        }
                                    });
                                }
                            }
                        }
                    });
                    if (lct != null) {
                        /* mark thread to be used by decrypter plugin */
                        owner = lct.getCurrentOwner();
                        lct.setCurrentOwner(wplg);
                        previousCrawler = lct.getCurrentLinkCrawler();
                        lct.setCurrentLinkCrawler(this);
                        /* save old logger/states */
                        oldLogger = lct.getLogger();
                        oldDebug = lct.isDebug();
                        oldVerbose = lct.isVerbose();
                        /* set new logger and set verbose/debug true */
                        lct.setLogger(logger);
                        lct.setVerbose(true);
                        lct.setDebug(true);
                    }
                    long startTime = System.currentTimeMillis();
                    try {
                        wplg.setCrawler(this);
                        wplg.setLinkCrawlerAbort(new LinkCrawlerAbort(generation, this));
                        decryptedPossibleLinks = wplg.decryptLink(cryptedLink);
                        /* in case we return normally, clear the logger */
                        logger.clear();
                    } finally {
                        /* close the logger */
                        logger.close();
                        wplg.setLinkCrawlerAbort(null);
                        distributeLinksDelayer.setDelayerEnabled(false);
                        /* make sure we dont have any unprocessed delayed Links */
                        distributeLinksDelayer.delayedrun();
                        long endTime = System.currentTimeMillis() - startTime;
                        lazyC.updateCrawlRuntime(endTime);
                    }
                } finally {
                    if (lct != null) {
                        /* reset thread to last known used state */
                        lct.setCurrentOwner(owner);
                        lct.setCurrentLinkCrawler(previousCrawler);
                        lct.setLogger(oldLogger);
                        lct.setVerbose(oldVerbose);
                        lct.setDebug(oldDebug);
                    }
                    /* remove distributer from plugin */
                    wplg.setDistributer(null);
                }
                if (decryptedPossibleLinks != null) {
                    dist.distribute(decryptedPossibleLinks.toArray(new DownloadLink[decryptedPossibleLinks.size()]));
                } else {
                    this.handleBrokenCrawledLink(cryptedLink);
                }
                if (brokenCrawler != null) {
                    try {
                        brokenCrawler.brokenCrawler(cryptedLink, this);
                    } catch (final Throwable e) {
                        LogController.CL().log(e);
                    }
                }
            } finally {
                /* restore old ClassLoader for current Thread */
                checkFinishNotify();
            }
        }
    }

    public java.util.List<CrawledLink> getCrawledLinks() {
        return crawledLinks;
    }

    public java.util.List<CrawledLink> getFilteredLinks() {
        return filteredLinks;
    }

    public java.util.List<CrawledLink> getBrokenLinks() {
        return brokenLinks;
    }

    public java.util.List<CrawledLink> getUnhandledLinks() {
        return unhandledLinks;
    }

    protected void handleBrokenCrawledLink(CrawledLink link) {
        this.brokenLinksCounter.incrementAndGet();
        getHandler().handleBrokenLink(link);
    }

    protected void handleUnhandledCryptedLink(CrawledLink link) {
        this.unhandledLinksCounter.incrementAndGet();
        getHandler().handleUnHandledLink(link);
    }

    protected void postprocessFinalCrawledLink(CrawledLink link) {
        final DownloadLink dl = link.getDownloadLink();
        if (dl != null) {
            final String[] sources = link.getSourceUrls();
            final HashSet<String> set = new HashSet<String>();

            set.add(dl.getPluginPatternMatcher());
            if (StringUtils.equals(dl.getPluginPatternMatcher(), dl.getContentUrl())) {
                dl.setContentUrl(null);
            }
            if (sources != null) {
                int sourceIndex = 0;
                if (sources.length > 1) {
                    final String clean = cleanURL(sources[0]);
                    if (StringUtils.equals(clean, sources[1])) {
                        sourceIndex++;
                    }
                }
                if (sources.length > sourceIndex && StringUtils.isEmpty(dl.getContentUrl())) {
                    final String cUrl = cleanURL(sources[sourceIndex]);
                    if (cUrl != null && set.add(cUrl)) {
                        dl.setContentUrl(cUrl);
                    }
                } else if (StringUtils.isNotEmpty(dl.getContentUrl())) {
                    set.add(dl.getContentUrl());
                    // if content url is set, then
                    // sources[0] is usually the pluginpattern,
                    // sources[1] is usually the contentURL without variant information
                    // if contentURL and patternurl do not equal, the container url is probably shifted
                    // containerIndex++;

                }
                sourceIndex++;
                if (StringUtils.isEmpty(dl.getContainerUrl())) {
                    for (int i = sourceIndex; i < sources.length; i++) {
                        sourceIndex = i;
                        final String containerURL = cleanURL(sources[i]);
                        if (containerURL != null) {
                            if (containerURL.startsWith("http://dummycnl.jdownloader.org")) {
                                // try to avoid dummycnl as containerurl;
                                continue;
                            }
                            if (set.add(containerURL)) {
                                dl.setContainerUrl(containerURL);
                            }
                        }
                        break;
                    }
                } else {
                    set.add(dl.getContainerUrl());
                }
                String referrerURL = dl.getReferrerUrl();
                if (referrerURL == null && LinkCrawler.this instanceof JobLinkCrawler) {
                    final LinkCollectingJob job = ((JobLinkCrawler) LinkCrawler.this).getJob();
                    if (job != null) {
                        /**
                         * restored links no longer have reference to job
                         */
                        referrerURL = job.getCustomSourceUrl();
                    }
                }
                if (StringUtils.isEmpty(dl.getOriginUrl())) {
                    String originURL = null;
                    for (int i = sources.length - 1; i > 1; i--) {
                        originURL = cleanURL(sources[i]);
                        if (StringUtils.equals(dl.getContentUrl(), originURL) || StringUtils.equals(dl.getPluginPatternMatcher(), originURL)) {
                            break;
                        }
                        if (StringUtils.equals(referrerURL, originURL)) {
                            originURL = null;
                            continue;
                        }
                        break;
                    }
                    if (originURL != null && (set.add(originURL) || StringUtils.equals(originURL, dl.getContainerUrl()))) {
                        dl.setOriginUrl(originURL);
                        if (StringUtils.equals(originURL, dl.getContainerUrl())) {
                            dl.setContainerUrl(null);
                        }
                    }
                }
                if (referrerURL != null && set.add(referrerURL)) {
                    dl.setReferrerUrl(referrerURL);
                }
            }
        }
    }

    protected void preprocessFinalCrawledLink(CrawledLink link) {
    }

    protected String cleanURL(String cUrl) {
        String protocol = HTMLParser.getProtocol(cUrl);
        if (protocol != null && !StringUtils.contains(cUrl, "decrypted.com/")) {
            if (StringUtils.containsIgnoreCase(protocol, "viajd")) {
                return cUrl.replaceFirst("viajd", "");
            } else if (StringUtils.containsIgnoreCase(protocol, "directhttp")) {
                return cUrl.replaceFirst("directhttp://", "");
            } else if (protocol.startsWith("http")) {
                return cUrl;
            }
        }
        return null;
    }

    protected void handleFinalCrawledLink(CrawledLink link) {
        if (link == null) {
            return;
        }
        link.setCreated(getCreated());
        preprocessFinalCrawledLink(link);
        CrawledLink origin = link.getOriginLink();
        CrawledLinkModifier customModifier = link.getCustomCrawledLinkModifier();
        link.setCustomCrawledLinkModifier(null);
        if (customModifier != null) {
            try {
                customModifier.modifyCrawledLink(link);
            } catch (final Throwable e) {
                LogController.CL().log(e);
            }
        }
        postprocessFinalCrawledLink(link);
        /* clean up some references */
        link.setBrokenCrawlerHandler(null);
        link.setUnknownHandler(null);
        /* specialHandling: Crypted A - > B - > Final C , and A equals C */
        // if link comes from flashgot, origin might be null
        boolean specialHandling = origin != null && (origin != link) && (StringUtils.equals(origin.getLinkID(), link.getLinkID()));
        if (isDoDuplicateFinderFinalCheck()) {
            if (duplicateFinderFinal.putIfAbsent(link.getLinkID(), this) != null && !specialHandling) {
                return;
            }
        }
        if (isCrawledLinkFiltered(link) == false) {
            /* link is not filtered, so we can process it normally */
            crawledLinksCounter.incrementAndGet();
            getHandler().handleFinalLink(link);
        }
    }

    protected boolean isCrawledLinkFiltered(CrawledLink link) {
        if (parentCrawler != null && getFilter() != parentCrawler.getFilter()) {
            if (parentCrawler.isCrawledLinkFiltered(link)) {
                return true;
            }
        }
        if (getFilter().dropByUrl(link)) {
            filteredLinksCounter.incrementAndGet();
            getHandler().handleFilteredLink(link);
            return true;
        }
        return false;
    }

    public int getCrawledLinksFoundCounter() {
        return crawledLinksCounter.get();
    }

    public int getFilteredLinksFoundCounter() {
        return filteredLinksCounter.get();
    }

    public int getBrokenLinksFoundCounter() {
        return brokenLinksCounter.get();
    }

    public int getUnhandledLinksFoundCounter() {
        return unhandledLinksCounter.get();
    }

    public int getProcessedLinksCounter() {
        return crawledLinksCounter.get() + filteredLinksCounter.get() + brokenLinksCounter.get() + processedLinksCounter.get();
    }

    protected LinkCrawlerHandler defaulHandlerFactory() {
        return new LinkCrawlerHandler() {

            public void handleFinalLink(CrawledLink link) {
                synchronized (crawledLinks) {
                    crawledLinks.add(link);
                }
            }

            public void handleFilteredLink(CrawledLink link) {
                synchronized (filteredLinks) {
                    filteredLinks.add(link);
                }
            }

            @Override
            public void handleBrokenLink(CrawledLink link) {
                synchronized (brokenLinks) {
                    brokenLinks.add(link);
                }
            }

            @Override
            public void handleUnHandledLink(CrawledLink link) {
                synchronized (unhandledLinks) {
                    unhandledLinks.add(link);
                }
            }
        };
    }

    public static LinkCrawlerFilter defaultFilterFactory() {
        return new LinkCrawlerFilter() {

            public boolean dropByUrl(CrawledLink link) {
                return false;
            }

            public boolean dropByFileProperties(CrawledLink link) {
                return false;
            };

        };
    }

    public void setFilter(LinkCrawlerFilter filter) {
        if (filter == null) {
            throw new IllegalArgumentException("filter is null");
        }
        this.filter = filter;
    }

    public void setHandler(LinkCrawlerHandler handler) {
        if (handler == null) {
            throw new IllegalArgumentException("handler is null");
        }
        this.handler = handler;
    }

    public LinkCrawlerFilter getFilter() {
        return filter;
    }

    public LinkCrawlerHandler getHandler() {
        return this.handler;
    }

}
