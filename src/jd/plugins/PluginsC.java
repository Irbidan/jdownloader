//    jDownloader - Downloadmanager
//    Copyright (C) 2008  JD-Team support@jdownloader.org
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

package jd.plugins;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.regex.Pattern;

import jd.controlling.linkcrawler.CrawledLink;
import jd.nutils.Formatter;

import org.appwork.exceptions.WTFException;
import org.appwork.storage.config.JsonConfig;
import org.appwork.uio.CloseReason;
import org.appwork.uio.ConfirmDialogInterface;
import org.appwork.uio.UIOManager;
import org.appwork.utils.Application;
import org.appwork.utils.Files;
import org.appwork.utils.Regex;
import org.appwork.utils.StringUtils;
import org.appwork.utils.logging2.LogSource;
import org.appwork.utils.swing.dialog.ConfirmDialog;
import org.appwork.utils.swing.dialog.Dialog;
import org.appwork.utils.swing.dialog.DialogClosedException;
import org.jdownloader.controlling.FileCreationManager;
import org.jdownloader.gui.translate._GUI;
import org.jdownloader.images.NewTheme;
import org.jdownloader.logging.LogController;
import org.jdownloader.settings.GeneralSettings;
import org.jdownloader.settings.GeneralSettings.DeleteContainerAction;
import org.jdownloader.translate._JDT;

/**
 * Dies ist die Oberklasse für alle Plugins, die Containerdateien nutzen können
 *
 * @author astaldo/JD-Team
 */

public abstract class PluginsC {

    private Pattern     pattern;

    private String      name;

    private long        version;

    protected LogSource logger = LogController.TRASH;

    public LogSource getLogger() {
        return logger;
    }

    public void setLogger(LogSource logger) {
        if (logger == null) {
            logger = LogController.TRASH;
        }
        this.logger = logger;
    }

    public PluginsC(String name, String pattern, String rev) {
        this.pattern = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
        this.name = name;
        try {
            version = Formatter.getRevision(rev);
        } catch (Throwable e) {
            logger.log(e);
            version = -1;
        }
    }

    private static final int         STATUS_NOTEXTRACTED     = 0;

    private static final int         STATUS_ERROR_EXTRACTING = 1;

    protected ArrayList<CrawledLink> cls                     = new ArrayList<CrawledLink>();

    protected String                 md5;
    protected byte[]                 k;

    private int                      status                  = STATUS_NOTEXTRACTED;
    protected boolean                askFileDeletion         = true;

    public abstract ContainerStatus callDecryption(File file) throws Exception;

    // @Override
    public synchronized boolean canHandle(final String data) {
        if (data == null) {
            return false;
        }
        final String match = new Regex(data, this.getSupportedLinks()).getMatch(-1);
        return match != null && match.equalsIgnoreCase(data);
    }

    public String createContainerString(ArrayList<DownloadLink> downloadLinks) {
        return null;
    }

    public Pattern getSupportedLinks() {
        return pattern;
    }

    public String getName() {
        return name;
    }

    public long getVersion() {
        return version;
    }

    /* hide links by default */
    public boolean hideLinks() {
        return true;
    }

    public abstract String[] encrypt(String plain);

    /**
     * Diese Methode liefert eine URL zurück, von der aus der Download gestartet werden kann
     *
     * @param downloadLink
     *            Der DownloadLink, dessen URL zurückgegeben werden soll
     * @return Die URL als String
     */
    public synchronized String extractDownloadURL(final DownloadLink downloadLink) {
        throw new WTFException("TODO: this should not happen at the moment");
    }

    /**
     * Liefert alle in der Containerdatei enthaltenen Dateien als DownloadLinks zurück.
     *
     * @param filename
     *            Die Containerdatei
     * @return Ein ArrayList mit DownloadLinks
     */
    public ArrayList<CrawledLink> getContainedDownloadlinks() {
        return cls == null ? new ArrayList<CrawledLink>() : cls;
    }

    protected boolean askFileDeletion() {
        return askFileDeletion;
    }

    public synchronized void initContainer(File file, final byte[] bs) throws IOException {
        if (file == null || !file.exists() || !file.isFile()) {
            return;
        }

        if (cls == null || cls.size() == 0) {
            logger.info("Init Container");
            if (bs != null) {
                k = bs;
            }
            try {
                callDecryption(file);
                if (askFileDeletion() == false) {
                    FileCreationManager.getInstance().delete(file, null);
                } else if (cls.size() > 0 && askFileDeletion()) {
                    switch (JsonConfig.create(GeneralSettings.class).getDeleteContainerFilesAfterAddingThemAction()) {
                    case ASK_FOR_DELETE:
                        final ConfirmDialog d = new ConfirmDialog(Dialog.STYLE_SHOW_DO_NOT_DISPLAY_AGAIN, _JDT._.AddContainerAction_delete_container_title(), _JDT._.AddContainerAction_delete_container_msg(file.toString()), NewTheme.I().getIcon("help", 32), _GUI._.lit_yes(), _GUI._.lit_no()) {
                            public String getDontShowAgainKey() {
                                return null;
                            }
                        };
                        final ConfirmDialogInterface s = UIOManager.I().show(ConfirmDialogInterface.class, d);
                        s.throwCloseExceptions();
                        if (s.getCloseReason() == CloseReason.OK) {
                            FileCreationManager.getInstance().delete(file, null);
                            if (s.isDontShowAgainSelected()) {
                                JsonConfig.create(GeneralSettings.class).setDeleteContainerFilesAfterAddingThemAction(DeleteContainerAction.DELETE);
                            }
                        } else {
                            if (s.isDontShowAgainSelected()) {
                                JsonConfig.create(GeneralSettings.class).setDeleteContainerFilesAfterAddingThemAction(DeleteContainerAction.DONT_DELETE);
                            }
                        }
                        break;
                    case DELETE:
                        FileCreationManager.getInstance().delete(file, null);
                        break;
                    case DONT_DELETE:

                    }
                }
                // doDecryption(filename);
            } catch (DialogClosedException e) {
            } catch (Throwable e) {
                logger.log(e);
            }
        }
    }

    public ArrayList<CrawledLink> decryptContainer(CrawledLink source) {
        if (source.getURL() == null) {
            return null;
        }
        ArrayList<CrawledLink> retLinks = null;
        boolean showException = true;
        try {
            /* extract filename from url */
            final String currentURI = new Regex(source.getURL(), "(file:/.+)").getMatch(0);
            if (currentURI != null) {
                final File file = new File(new URI(currentURI));
                if (file != null && file.exists()) {
                    final CrawledLink origin = source.getOriginLink();
                    if (origin != null && !StringUtils.containsIgnoreCase(origin.getURL(), "file:/")) {
                        askFileDeletion = false;
                    } else if (origin != null) {
                        final String originURI = new Regex(origin.getURL(), "(file:/.+)").getMatch(0);
                        if (originURI != null && !currentURI.equalsIgnoreCase(originURI)) {
                            logger.fine("Do not ask - just delete: " + origin.getURL());
                            askFileDeletion = false;
                        }
                    }
                    if (askFileDeletion) {
                        final String tmp = Application.getTempResource("").getAbsolutePath();
                        final String rel = Files.getRelativePath(tmp, file.getAbsolutePath());
                        if (rel != null) {
                            logger.fine("Do not ask - just delete: " + origin.getURL());
                            askFileDeletion = false;
                        }
                    }
                    initContainer(file, null);
                    retLinks = getContainedDownloadlinks();
                }
            } else {
                throw new Throwable("Invalid Container: " + source.getURL());
            }
        } catch (Throwable e) {
            /*
             * damn, something must have gone really really bad, lets keep the log
             */

            logger.log(e);
        }
        if (retLinks == null && showException) {
            /*
             * null as return value? something must have happened, do not clear log
             */
            logger.severe("ContainerPlugin out of date: " + this + " :" + getVersion());
        }
        return retLinks;
    }
}