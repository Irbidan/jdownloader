package org.jdownloader.captcha.v2.solver.captchabrotherhood;

import java.io.IOException;
import java.net.URL;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import jd.http.Browser;
import jd.http.requests.PostRequest;
import jd.nutils.encoding.Encoding;

import org.appwork.storage.config.JsonConfig;
import org.appwork.utils.Application;
import org.appwork.utils.IO;
import org.appwork.utils.StringUtils;
import org.jdownloader.captcha.v2.AbstractResponse;
import org.jdownloader.captcha.v2.Challenge;
import org.jdownloader.captcha.v2.ChallengeResponseValidation;
import org.jdownloader.captcha.v2.challenge.stringcaptcha.BasicCaptchaChallenge;
import org.jdownloader.captcha.v2.solver.CESChallengeSolver;
import org.jdownloader.captcha.v2.solver.CESSolverJob;
import org.jdownloader.captcha.v2.solver.jac.SolverException;
import org.jdownloader.captcha.v2.solverjob.SolverJob;
import org.jdownloader.gui.IconKey;
import org.jdownloader.gui.translate._GUI;
import org.jdownloader.images.NewTheme;
import org.jdownloader.logging.LogController;
import org.jdownloader.settings.staticreferences.CFG_CBH;

public class CBSolver extends CESChallengeSolver<String> implements ChallengeResponseValidation {

    private String                     accountStatusString;
    private static final CBSolver      INSTANCE   = new CBSolver();
    private ThreadPoolExecutor         threadPool = new ThreadPoolExecutor(0, 1, 30000, TimeUnit.MILLISECONDS, new LinkedBlockingDeque<Runnable>(), Executors.defaultThreadFactory());
    private CaptchaBrotherHoodSettings config;

    public static CBSolver getInstance() {
        return INSTANCE;
    }

    @Override
    public Class<String> getResultType() {
        return String.class;
    }

    @Override
    public CBSolverService getService() {
        return (CBSolverService) super.getService();
    }

    private CBSolver() {
        super(new CBSolverService(), 1);
        config = JsonConfig.create(CaptchaBrotherHoodSettings.class);
        getService().setSolver(this);
        // we have "native" cbh support now.
        Application.getResource("jd/captcha/methods/captchaBrotherhood/jacinfo.xml").renameTo(Application.getResource("jd/captcha/methods/captchaBrotherhood/jacinfo.xml.deprecated"));
    }

    @Override
    public boolean canHandle(Challenge<?> c) {
        if (c instanceof BasicCaptchaChallenge && super.canHandle(c)) {
            try {
                checkForEnoughCredits();
            } catch (SolverException e) {
                return false;
            }
            return true;
        }
        return false;
    }

    @Override
    public String getAccountStatusString() {
        return accountStatusString;
    }

    protected boolean validateLogins() {
        if (!CFG_CBH.ENABLED.isEnabled()) {
            return false;
        }
        if (StringUtils.isEmpty(CFG_CBH.USER.getValue())) {
            return false;
        }
        if (StringUtils.isEmpty(CFG_CBH.PASS.getValue())) {
            return false;
        }

        return true;
    }

    private AtomicInteger       counterSolved      = new AtomicInteger();
    private AtomicInteger       counterInterrupted = new AtomicInteger();
    private AtomicInteger       counter            = new AtomicInteger();
    private volatile CBHAccount lastAccount        = null;

    @Override
    protected void solveCES(CESSolverJob<String> job) throws InterruptedException, SolverException {
        checkForEnoughCredits();
        CBHAccount acc = loadAccount();
        if (StringUtils.isEmpty(acc.getError())) {
            accountStatusString = acc.getBalance() + " Credits";
        } else {
            accountStatusString = acc.getError();
        }

        checkForEnoughCredits();

        BasicCaptchaChallenge challenge = (BasicCaptchaChallenge) job.getChallenge();
        String user = config.getUser();
        String password = config.getPass();

        job.showBubble(this);
        checkInterruption();
        try {
            counter.incrementAndGet();
            String url = "http://www.captchabrotherhood.com/sendNewCaptcha.aspx?username=" + Encoding.urlEncode(user) + "&password=" + Encoding.urlEncode(password) + "&captchaSource=jdPlugin&captchaSite=-1&timeout=80&version=1.2.1";
            byte[] data = IO.readFile(challenge.getImageFile());
            job.setStatus(_GUI._.DeathByCaptchaSolver_solveBasicCaptchaChallenge_uploading(), NewTheme.I().getIcon(IconKey.ICON_UPLOAD, 20));

            final Browser br = new Browser();

            br.setDebug(true);
            br.setVerbose(true);
            //
            final PostRequest request = new PostRequest(url);
            // server answers with internal server error if we do not use text/html
            request.setContentType("text/html");
            request.setPostBytes(data);
            String ret = br.loadConnection(br.openRequestConnection(request)).getHtmlCode();

            job.setStatus(_GUI._.DeathByCaptchaSolver_solveBasicCaptchaChallenge_solving(), NewTheme.I().getIcon(IconKey.ICON_UPLOAD, 20));

            job.getLogger().info("Send Captcha. Answer: " + ret);
            if (!ret.startsWith("OK-")) {
                throw new SolverException(ret);
            }
            String captchaID = ret.substring(3);
            data = null;
            Thread.sleep(6000);
            while (true) {
                Thread.sleep(1000);
                url = "http://www.captchabrotherhood.com/askCaptchaResult.aspx?username=" + Encoding.urlEncode(config.getUser()) + "&password=" + Encoding.urlEncode(config.getPass()) + "&captchaID=" + Encoding.urlEncode(captchaID) + "&version=1.1.7";
                job.getLogger().info("Ask " + url);
                ret = br.getPage(url);
                job.getLogger().info("Answer " + ret);
                if (ret.startsWith("OK-answered-")) {
                    counterSolved.incrementAndGet();

                    job.setAnswer(new CaptchaCBHResponse(challenge, this, ret.substring("OK-answered-".length()), 100, captchaID));
                    return;
                }
                checkInterruption();

            }
        } catch (InterruptedException e) {
            counterInterrupted.incrementAndGet();
            throw e;

        } catch (IOException e) {
            job.getLogger().log(e);
            counterInterrupted.incrementAndGet();
        } finally {

        }

    }

    private void checkForEnoughCredits() throws SolverException {
        final CBHAccount lLastAccount = lastAccount;
        if (lLastAccount != null) {
            // valid cached account
            if (StringUtils.equals(config.getUser(), lLastAccount.getUser())) {
                // user did not change
                if ((System.currentTimeMillis() - lLastAccount.getCreateTime()) < 5 * 60 * 1000l) {
                    // cache is not older than 5 minutes
                    if (lLastAccount.getBalance() < 10) {
                        throw new SolverException("Not Enough Credits for Task");
                    }
                    if (lLastAccount.getError() != null) {
                        throw new SolverException("CBH: " + lLastAccount.getError());
                    }
                }
            }
        }
    }

    public CBHAccount loadAccount() {
        final CBHAccount ret = new CBHAccount();
        ret.setRequests(counter.get());
        ret.setSkipped(counterInterrupted.get());
        ret.setSolved(counterSolved.get());
        ret.setUser(config.getUser());
        try {
            final Browser br = new Browser();
            br.setDebug(true);
            br.setVerbose(true);
            String result = br.postPage("http://www.captchabrotherhood.com/askCredits.aspx?username=" + Encoding.urlEncode(config.getUser()) + "&password=" + Encoding.urlEncode(config.getPass()) + "&version=1.1.8", "");
            if (result.startsWith("OK-")) {
                ret.setBalance(Integer.parseInt(result.substring(3)));
            } else {
                ret.setError(result);
            }
        } catch (Exception e) {
            ret.setError(e.getMessage());
        }
        lastAccount = ret;
        return ret;
    }

    @Override
    public void setValid(final AbstractResponse<?> response, SolverJob<?> job) {
    }

    @Override
    public void setUnused(final AbstractResponse<?> response, SolverJob<?> job) {
    }

    @Override
    public void setInvalid(final AbstractResponse<?> response, SolverJob<?> job) {
        threadPool.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    String captchaID = ((CaptchaCBHResponse) response).getCaptchaCBHID();
                    Browser br = new Browser();
                    br.setDebug(true);
                    br.setVerbose(true);
                    String ret = "";
                    ret = br.getPage(new URL("http://www.captchabrotherhood.com/complainCaptcha.aspx?username=" + Encoding.urlEncode(config.getUser()) + "&password=" + Encoding.urlEncode(config.getPass()) + "&captchaID=" + Encoding.urlEncode(captchaID) + "&version=1.1.8"));
                } catch (final Throwable e) {
                    LogController.CL(true).log(e);
                }
            }
        });
    }

}
