package com.bharath.digicampusimporttest;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final String DIGICAMPUS_URL =
            "https://rajalakshmi.digiicampus.com/";

    private static final String PREFS_NAME =
            "digicampus_prefs";

    private static final String KEY_HAS_OPENED =
            "hasOpenedDigiCampus";

    private static final String KEY_LAST_URL =
            "lastUrl";

    private static final String KEY_WEBVIEW_VISIBLE =
            "webviewVisible";

    private WebView webView;
    private Button openButton;
    private Button importButton;
    private ScrollView resultScroll;
    private TextView resultText;

    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(
                PREFS_NAME,
                MODE_PRIVATE
        );

        webView = findViewById(R.id.webView);
        openButton = findViewById(R.id.openButton);
        importButton = findViewById(R.id.importButton);
        resultScroll = findViewById(R.id.resultScroll);
        resultText = findViewById(R.id.resultText);

        setupWebView();
        setupBackButtonHandling();

        openButton.setOnClickListener(v -> {
            prefs.edit()
                    .putBoolean(KEY_HAS_OPENED, true)
                    .apply();

            showWebViewUi();

            webView.loadUrl(DIGICAMPUS_URL);
        });

        importButton.setOnClickListener(v ->
                importAttendance()
        );

        restoreOnStartup(savedInstanceState);
    }

    // ============================================================
    // STARTUP / WEBVIEW PERSISTENCE
    // ============================================================

    private void restoreOnStartup(Bundle savedInstanceState) {

        boolean hasOpenedBefore =
                prefs.getBoolean(
                        KEY_HAS_OPENED,
                        false
                );

        if (savedInstanceState != null
                && savedInstanceState.getBoolean(
                KEY_WEBVIEW_VISIBLE,
                false)) {

            boolean restored =
                    webView.restoreState(
                            savedInstanceState
                    ) != null;

            if (restored) {
                showWebViewUi();
                return;
            }
        }

        if (hasOpenedBefore) {

            showWebViewUi();

            String lastUrl =
                    prefs.getString(
                            KEY_LAST_URL,
                            null
                    );

            if (lastUrl != null
                    && !lastUrl.isEmpty()) {

                webView.loadUrl(lastUrl);

            } else {

                webView.loadUrl(DIGICAMPUS_URL);
            }

            return;
        }

        showInitialUi();
    }

    private void showWebViewUi() {
        openButton.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
        importButton.setVisibility(View.VISIBLE);
        resultScroll.setVisibility(View.GONE);
    }

    private void showInitialUi() {
        openButton.setVisibility(View.VISIBLE);
        webView.setVisibility(View.GONE);
        importButton.setVisibility(View.GONE);
        resultScroll.setVisibility(View.GONE);
    }

    // ============================================================
    // BACK BUTTON
    // ============================================================

    private void setupBackButtonHandling() {

        getOnBackPressedDispatcher().addCallback(
                this,
                new OnBackPressedCallback(true) {

                    @Override
                    public void handleOnBackPressed() {

                        // If result screen is showing,
                        // return to DigiCampus.
                        if (resultScroll.getVisibility() == View.VISIBLE) {
                            resultScroll.setVisibility(View.GONE);
                            webView.setVisibility(View.VISIBLE);
                            importButton.setVisibility(View.VISIBLE);
                            return;
                        }

                        // If DigiCampus is visible,
                        // navigate through WebView history.
                        if (webView.getVisibility() == View.VISIBLE) {

                            if (webView.canGoBack()) {
                                webView.goBack();
                                return;
                            }

                            // No WebView history left.
                            showInitialUi();
                            return;
                        }

                        // Already on initial screen.
                        setEnabled(false);
                        getOnBackPressedDispatcher().onBackPressed();
                    }
                }
        );
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {

        if (webView != null) {
            webView.saveState(outState);
        }

        outState.putBoolean(
                KEY_WEBVIEW_VISIBLE,
                webView != null && webView.getVisibility() == View.VISIBLE
        );

        super.onSaveInstanceState(outState);
    }

    // ============================================================
    // WEBVIEW SETUP
    // ============================================================

    private void setupWebView() {

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(
                new WebViewClient() {

                    @Override
                    public void onPageFinished(WebView view, String url) {
                        super.onPageFinished(view, url);

                        if (url != null && !url.equals("about:blank")) {
                            prefs.edit()
                                    .putString(KEY_LAST_URL, url)
                                    .apply();
                        }
                    }
                }
        );
    }

    // ============================================================
    // ATTENDANCE IMPORTER
    // ============================================================

    private void importAttendance() {
        if (webView == null) {
            showResult("WebView is not initialized.");
            return;
        }

        /*
         * JS script that detects which attendance view is displayed:
         * 1) Main summary page (.course-att-comp count > 0) -> UNCHANGED
         * 2) Individual subject page -> Processes summary + marked & unmarked session records
         * 3) Neither (returns prompt message)
         */
        String javascript =
                "(function() {" +
                "  try {" +
                "    var courses = document.querySelectorAll('.course-att-comp');" +
                "    if (courses && courses.length > 0) {" +
                "      var subjects = [];" +
                "      var errors = [];" +
                "      for (var i = 0; i < courses.length; i++) {" +
                "        var course = courses[i];" +
                "        var header = course.querySelector('.att-agg-course-header') || course;" +
                "        var nameElem = header.querySelector('.col-xs-4.ng-binding') || header.querySelector('.col-xs-4');" +
                "        var valElems = header.querySelectorAll('.col-xs-2.text-center.ng-binding');" +
                "        if (!valElems || valElems.length < 3) {" +
                "          valElems = header.querySelectorAll('.col-xs-2.text-center');" +
                "        }" +
                "        if (!nameElem || !valElems || valElems.length < 3) {" +
                "          errors.push('Row ' + (i + 1) + ': Missing elements');" +
                "          continue;" +
                "        }" +
                "        var name = (nameElem.textContent || '').replace(/\\s+/g, ' ').trim();" +
                "        var rawAtt = (valElems[0].textContent || '').replace(/[^0-9]/g, '');" +
                "        var rawSch = (valElems[1].textContent || '').replace(/[^0-9]/g, '');" +
                "        var rawPct = (valElems[2].textContent || '').replace(/[^0-9.]/g, '');" +
                "        var attended = parseInt(rawAtt, 10);" +
                "        var scheduled = parseInt(rawSch, 10);" +
                "        var percentage = parseFloat(rawPct);" +
                "        if (!name || isNaN(attended) || isNaN(scheduled) || isNaN(percentage)) {" +
                "          errors.push('Row ' + (i + 1) + ': Invalid data');" +
                "          continue;" +
                "        }" +
                "        subjects.push({" +
                "          name: name," +
                "          attended: attended," +
                "          scheduled: scheduled," +
                "          percentage: percentage" +
                "        });" +
                "      }" +
                "      return JSON.stringify({" +
                "        viewType: 'main'," +
                "        count: subjects.length," +
                "        subjects: subjects," +
                "        errors: errors" +
                "      });" +
                "    }" +
                "    var present = -1;" +
                "    var absent = -1;" +
                "    var onDuty = -1;" +
                "    var statusBlocks = document.querySelectorAll('.att-agg-status');" +
                "    if (statusBlocks && statusBlocks.length > 0) {" +
                "      for (var sb = 0; sb < statusBlocks.length; sb++) {" +
                "        var block = statusBlocks[sb];" +
                "        var blockText = (block.textContent || '').replace(/\\s+/g, ' ').trim();" +
                "        var numInBlock = -1;" +
                "        var countElems = block.querySelectorAll('.att-agg-status-count, .ng-binding, b, strong, span');" +
                "        for (var ce = 0; ce < countElems.length; ce++) {" +
                "          var elemTxt = (countElems[ce].textContent || '').trim();" +
                "          if (/^\\d+$/.test(elemTxt)) {" +
                "            numInBlock = parseInt(elemTxt, 10);" +
                "            break;" +
                "          }" +
                "        }" +
                "        if (numInBlock === -1) {" +
                "          var m = blockText.match(/(\\d+)/);" +
                "          if (m) numInBlock = parseInt(m[1], 10);" +
                "        }" +
                "        if (numInBlock !== -1) {" +
                "          if (/PRESENT/i.test(blockText)) {" +
                "            present = numInBlock;" +
                "          } else if (/ABSENT/i.test(blockText)) {" +
                "            absent = numInBlock;" +
                "          } else if (/ON\\s*DUTY|OD/i.test(blockText)) {" +
                "            onDuty = numInBlock;" +
                "          }" +
                "        }" +
                "      }" +
                "    }" +
                "    if (present === -1 || absent === -1) {" +
                "      var header = document.querySelector('.overall-attendance-header');" +
                "      var summaryContainer = null;" +
                "      if (header) {" +
                "        var curr = header;" +
                "        var level = 0;" +
                "        while (curr && level < 5) {" +
                "          var currTxt = curr.textContent || '';" +
                "          if (/present/i.test(currTxt) && /absent/i.test(currTxt)) {" +
                "            summaryContainer = curr;" +
                "            break;" +
                "          }" +
                "          var sib = curr.nextElementSibling;" +
                "          while (sib) {" +
                "            var sibTxt = sib.textContent || '';" +
                "            if (/present/i.test(sibTxt) && /absent/i.test(sibTxt)) {" +
                "              summaryContainer = sib;" +
                "              break;" +
                "            }" +
                "            sib = sib.nextElementSibling;" +
                "          }" +
                "          if (summaryContainer) break;" +
                "          curr = curr.parentElement;" +
                "          level++;" +
                "        }" +
                "      }" +
                "      if (summaryContainer) {" +
                "        var containerTxt = (summaryContainer.innerText || summaryContainer.textContent || '').replace(/\\r/g, '');" +
                "        if (present === -1) {" +
                "          var presMatch = containerTxt.match(/PRESENT\\s*[:\\-]?\\s*(\\d+)/i) || containerTxt.match(/(\\d+)\\s*PRESENT/i);" +
                "          if (presMatch) present = parseInt(presMatch[1], 10);" +
                "        }" +
                "        if (absent === -1) {" +
                "          var absMatch = containerTxt.match(/ABSENT\\s*[:\\-]?\\s*(\\d+)/i) || containerTxt.match(/(\\d+)\\s*ABSENT/i);" +
                "          if (absMatch) absent = parseInt(absMatch[1], 10);" +
                "        }" +
                "      }" +
                "    }" +
                "    if (present !== -1 || absent !== -1 || (statusBlocks && statusBlocks.length > 0)) {" +
                "      var subjectName = '';" +
                "      var lectureMatches = [];" +
                "      var allDomElements = document.querySelectorAll('*');" +
                "      for (var d = 0; d < allDomElements.length; d++) {" +
                "        var elem = allDomElements[d];" +
                "        var eTxt = (elem.textContent || '').replace(/\\s+/g, ' ').trim();" +
                "        if (/\\[\\s*(lecture|practical|tutorial|lab|theory)\\s*\\]/i.test(eTxt)) {" +
                "          var childHasMatch = false;" +
                "          for (var ch = 0; ch < elem.children.length; ch++) {" +
                "            if (/\\[\\s*(lecture|practical|tutorial|lab|theory)\\s*\\]/i.test(elem.children[ch].textContent || '')) {" +
                "              childHasMatch = true;" +
                "              break;" +
                "            }" +
                "          }" +
                "          if (!childHasMatch) {" +
                "            var p = elem.parentElement;" +
                "            lectureMatches.push({" +
                "              tagName: elem.tagName," +
                "              className: elem.className || ''," +
                "              id: elem.id || ''," +
                "              textContent: eTxt.substring(0, 150)," +
                "              parentTag: p ? p.tagName : ''," +
                "              parentClass: p ? (p.className || '') : ''," +
                "              parentText: p ? (p.textContent || '').replace(/\\s+/g, ' ').trim().substring(0, 200) : ''" +
                "            });" +
                "            if (!subjectName) {" +
                "              var rawBeforeBracket = eTxt.split(/\\[\\s*(lecture|practical|tutorial|lab|theory)\\s*\\]/i)[0];" +
                "              if (rawBeforeBracket && rawBeforeBracket.trim().length > 2) {" +
                "                subjectName = rawBeforeBracket.trim();" +
                "              }" +
                "            }" +
                "          }" +
                "        }" +
                "      }" +
                "      var ignoreRegex = /^(present|absent|on\\s*duty|attendance|overall|dashboard|profile|home|academics|rajalakshmi|menu|user|student|total|percentage|if\\s*you\\s*think|you\\s*do\\s*not\\s*have|permissions|permission|contact|support|disclaimer|copyright|notice|help|error)/i;" +
                "      if (!subjectName) {" +
                "        var titleElems = document.querySelectorAll('.course-title, .subject-title, .course-name, .selected-course, .breadcrumb .active, .sub-title, h2, h3');" +
                "        for (var ti = 0; ti < titleElems.length; ti++) {" +
                "          var tTxt = (titleElems[ti].textContent || '').replace(/\\s+/g, ' ').trim();" +
                "          if (tTxt && tTxt.length >= 3 && !ignoreRegex.test(tTxt)) {" +
                "            subjectName = tTxt;" +
                "            break;" +
                "          }" +
                "        }" +
                "      }" +
                "      if (!subjectName) {" +
                "        var headings = document.querySelectorAll('h1, h2, h3, h4, h5');" +
                "        for (var hi = 0; hi < headings.length; hi++) {" +
                "          var hTxt = (headings[hi].textContent || '').replace(/\\s+/g, ' ').trim();" +
                "          if (hTxt && hTxt.length >= 5 && hTxt.length < 80 && !ignoreRegex.test(hTxt)) {" +
                "            subjectName = hTxt;" +
                "            break;" +
                "          }" +
                "        }" +
                "      }" +
                "      if (subjectName) {" +
                "        subjectName = subjectName.replace(/\\[\\s*(lecture|practical|tutorial|lab|theory)\\s*\\]/gi, '').trim();" +
                "      }" +
                "      var totalSessions = -1;" +
                "      var percentage = -1;" +
                "      if (present !== -1 && absent !== -1) {" +
                "        totalSessions = present + absent;" +
                "        if (totalSessions > 0) {" +
                "          percentage = Math.round((present / totalSessions) * 10000) / 100;" +
                "        }" +
                "      }" +
                "      var ngRepeats = document.querySelectorAll('[ng-repeat]');" +
                "      var candidateElements = [];" +
                "      for (var nr = 0; nr < ngRepeats.length; nr++) {" +
                "        var nElem = ngRepeats[nr];" +
                "        if (nElem.classList.contains('course-att-comp') || nElem.querySelector('.course-att-comp')) continue;" +
                "        if (nElem.classList.contains('att-agg-status') || nElem.querySelector('.att-agg-status')) continue;" +
                "        var nTxt = (nElem.textContent || '').replace(/\\s+/g, ' ').trim();" +
                "        if (/\\b(PRESENT|ABSENT|ON\\s*DUTY|OD|P|A|UNMARKED|UPCOMING|SCHEDULED|PENDING)\\b/i.test(nTxt) ||" +
                "            /\\b(?:\\d{1,2}\\s+(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\\s+\\d{4}|\\d{1,2}[\\/\\-\\.]\\d{1,2}[\\/\\-\\.]\\d{2,4}|\\d{1,2}:\\d{2}\\s*(?:AM|PM))\\b/i.test(nTxt)) {" +
                "          candidateElements.push(nElem);" +
                "        }" +
                "      }" +
                "      if (candidateElements.length === 0) {" +
                "        var allScope = document.querySelectorAll('.ng-scope, tr, .list-group-item, div.row, div[class*=\"col-\"]');" +
                "        for (var sc = 0; sc < allScope.length; sc++) {" +
                "          var sElem = allScope[sc];" +
                "          if (sElem.querySelector('.att-agg-status') || sElem.classList.contains('att-agg-status') || sElem.querySelector('.overall-attendance-header') || sElem.classList.contains('overall-attendance-header')) continue;" +
                "          var sTxt = (sElem.textContent || '').replace(/\\s+/g, ' ').trim();" +
                "          if (/\\b(PRESENT|ABSENT|ON\\s*DUTY|OD|P|A|UNMARKED|UPCOMING|SCHEDULED|PENDING)\\b/i.test(sTxt) ||" +
                "              /\\b(?:\\d{1,2}\\s+(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\\s+\\d{4}|\\d{1,2}[\\/\\-\\.]\\d{1,2}[\\/\\-\\.]\\d{2,4}|\\d{1,2}:\\d{2}\\s*(?:AM|PM))\\b/i.test(sTxt)) {" +
                "            candidateElements.push(sElem);" +
                "          }" +
                "        }" +
                "      }" +
                "      var finalRecordElems = [];" +
                "      for (var m1 = 0; m1 < candidateElements.length; m1++) {" +
                "        var isParent = false;" +
                "        for (var m2 = 0; m2 < candidateElements.length; m2++) {" +
                "          if (m1 !== m2 && candidateElements[m1].contains(candidateElements[m2])) {" +
                "            isParent = true;" +
                "            break;" +
                "          }" +
                "        }" +
                "        if (!isParent) {" +
                "          finalRecordElems.push(candidateElements[m1]);" +
                "        }" +
                "      }" +
                "      var markedSessions = [];" +
                "      var unmarkedSessions = [];" +
                "      for (var k = 0; k < finalRecordElems.length; k++) {" +
                "        var recItem = finalRecordElems[k];" +
                "        var itemText = (recItem.textContent || '').replace(/\\s+/g, ' ').trim();" +
                "        var dateMatch = itemText.match(/\\b\\d{1,2}\\s+(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\\s+\\d{4}\\b/i) || itemText.match(/\\b\\d{1,2}[\\/\\-\\.]\\d{1,2}[\\/\\-\\.]\\d{2,4}\\b/);" +
                "        var timeMatch = itemText.match(/\\b\\d{1,2}:\\d{2}(?::\\d{2})?\\s*(?:AM|PM)?\\b/i);" +
                "        var statusMatch = itemText.match(/\\b(PRESENT\\s*\\([PA]\\)|ABSENT\\s*\\([PA]\\)|PRESENT|ABSENT|ON\\s*DUTY|OD)\\b/i);" +
                "        var date = dateMatch ? dateMatch[0].trim() : '';" +
                "        var time = timeMatch ? timeMatch[0].trim() : '';" +
                "        var status = '';" +
                "        if (statusMatch) {" +
                "          status = statusMatch[0].trim().toUpperCase();" +
                "        } else {" +
                "          var badges = recItem.querySelectorAll('span, div, b, strong, p, td');" +
                "          for (var bg = 0; bg < badges.length; bg++) {" +
                "            var bTxt = (badges[bg].textContent || '').trim().toUpperCase();" +
                "            if (bTxt === 'PRESENT (P)' || bTxt === 'PRESENT' || bTxt === 'P') { status = 'PRESENT (P)'; break; }" +
                "            if (bTxt === 'ABSENT (A)' || bTxt === 'ABSENT' || bTxt === 'A') { status = 'ABSENT (A)'; break; }" +
                "            if (bTxt === 'ON DUTY' || bTxt === 'ON-DUTY' || bTxt === 'OD') { status = 'ON DUTY'; break; }" +
                "          }" +
                "        }" +
                "        if (!status) {" +
                "          var html = recItem.outerHTML || '';" +
                "          if (/\\b(present|att-p|badge-success|text-success|status-p)\\b/i.test(html)) status = 'PRESENT (P)';" +
                "          else if (/\\b(absent|att-a|badge-danger|text-danger|status-a)\\b/i.test(html)) status = 'ABSENT (A)';" +
                "          else if (/\\b(onduty|att-od|badge-warning|badge-info|status-od)\\b/i.test(html)) status = 'ON DUTY';" +
                "        }" +
                "        if (status === 'P') status = 'PRESENT (P)';" +
                "        if (status === 'A') status = 'ABSENT (A)';" +
                "        if (status === 'OD') status = 'ON DUTY';" +
                "        if (status && (date || time)) {" +
                "          markedSessions.push({" +
                "            date: date," +
                "            time: time," +
                "            status: status," +
                "            rawText: itemText" +
                "          });" +
                "        } else if (date || time || /\\b(UNMARKED|UPCOMING|SCHEDULED|PENDING)\\b/i.test(itemText)) {" +
                "          unmarkedSessions.push({" +
                "            date: date," +
                "            time: time," +
                "            status: 'UNMARKED'," +
                "            rawText: itemText" +
                "          });" +
                "        }" +
                "      }" +
                "      return JSON.stringify({" +
                "        viewType: 'individual'," +
                "        subjectName: subjectName || 'Individual Subject'," +
                "        present: present," +
                "        absent: absent," +
                "        onDuty: onDuty," +
                "        total: totalSessions," +
                "        percentage: percentage," +
                "        markedSessions: markedSessions," +
                "        unmarkedSessions: unmarkedSessions," +
                "        diagnostic: {" +
                "          lectureMatches: lectureMatches" +
                "        }" +
                "      });" +
                "    }" +
                "    return JSON.stringify({" +
                "      viewType: 'none'," +
                "      message: 'Please open the main Attendance Records page first.'" +
                "    });" +
                "  } catch(e) {" +
                "    return JSON.stringify({ viewType: 'error', error: String(e) });" +
                "  }" +
                "})();";

        webView.evaluateJavascript(javascript, new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String rawValue) {
                JSONObject result = parseJsResult(rawValue);

                if (result == null) {
                    showResult("DigiCampus did not return a valid result.\n\nPlease ensure the page is fully loaded and try again.");
                    return;
                }

                String viewType = result.optString("viewType", "none");

                if ("main".equals(viewType)) {
                    JSONArray subjects = result.optJSONArray("subjects");
                    if (subjects != null && subjects.length() > 0) {
                        displayMainAttendance(subjects, result.optJSONArray("errors"));
                    } else {
                        showResult("Please open the main Attendance Records page first.");
                    }
                } else if ("individual".equals(viewType)) {
                    displayIndividualAttendance(result);
                } else {
                    showResult(result.optString("message", "Please open the main Attendance Records page first."));
                }
            }
        });
    }

    // ============================================================
    // DISPLAY & PARSING UTILITIES
    // ============================================================

    private void displayMainAttendance(JSONArray subjects, JSONArray errors) {
        StringBuilder output = new StringBuilder();

        output.append("Attendance Imported Successfully\n\n");
        output.append("Imported ").append(subjects.length()).append(" subjects\n\n");

        for (int i = 0; i < subjects.length(); i++) {
            try {
                JSONObject subject = subjects.getJSONObject(i);
                String name = subject.getString("name");
                int attended = subject.getInt("attended");
                int scheduled = subject.getInt("scheduled");
                double percentage = subject.getDouble("percentage");

                output.append(name).append("\n");
                output.append(attended).append(" / ").append(scheduled).append("\n");
                output.append(String.format(Locale.US, "%.2f%%", percentage)).append("\n\n");
            } catch (Exception e) {
                // skip corrupted item if any
            }
        }

        if (errors != null && errors.length() > 0) {
            output.append("Rows skipped:\n");
            for (int k = 0; k < errors.length(); k++) {
                output.append("• ").append(errors.optString(k)).append("\n");
            }
        }

        showResult(output.toString().trim());
    }

    private void displayIndividualAttendance(JSONObject result) {
        String subjectName = result.optString("subjectName", "Individual Subject");
        int present = result.optInt("present", -1);
        int absent = result.optInt("absent", -1);
        int onDuty = result.optInt("onDuty", -1);
        int total = result.optInt("total", -1);
        double percentage = result.optDouble("percentage", -1.0);
        JSONArray markedSessions = result.optJSONArray("markedSessions");
        JSONArray unmarkedSessions = result.optJSONArray("unmarkedSessions");

        StringBuilder output = new StringBuilder();
        output.append("Individual Subject Attendance\n\n");

        if (!subjectName.isEmpty() && !subjectName.equalsIgnoreCase("Individual Subject")) {
            output.append("Subject: ").append(subjectName).append("\n\n");
        }

        if (present != -1) {
            output.append("Present: ").append(present).append("\n");
        }
        if (absent != -1) {
            output.append("Absent: ").append(absent).append("\n");
        }
        if (onDuty != -1) {
            output.append("On Duty: ").append(onDuty).append("\n");
        }
        if (total != -1) {
            output.append("Total Marked Sessions: ").append(total).append("\n");
        }
        if (percentage >= 0) {
            output.append(String.format(Locale.US, "Percentage: %.2f%%", percentage)).append("\n");
        }

        output.append("\n----------------------------------------\n\n");

        int markedCount = (markedSessions != null) ? markedSessions.length() : 0;
        output.append("Marked Attendance Records (").append(markedCount).append("):\n\n");

        if (markedSessions != null && markedSessions.length() > 0) {
            for (int m = 0; m < markedSessions.length(); m++) {
                JSONObject s = markedSessions.optJSONObject(m);
                if (s != null) {
                    String date = s.optString("date", "");
                    String time = s.optString("time", "");
                    String status = s.optString("status", "");
                    String rawText = s.optString("rawText", "");

                    output.append("• ");
                    if (!date.isEmpty()) output.append(date);
                    if (!time.isEmpty()) {
                        if (!date.isEmpty()) output.append("  |  ");
                        output.append(time);
                    }
                    if (!status.isEmpty()) {
                        if (!date.isEmpty() || !time.isEmpty()) output.append("  |  ");
                        output.append(status);
                    }
                    if (date.isEmpty() && time.isEmpty() && status.isEmpty() && !rawText.isEmpty()) {
                        output.append(rawText);
                    }
                    output.append("\n\n");
                }
            }
        } else {
            output.append("No marked attendance records found on this page.\n\n");
        }

        output.append("----------------------------------------\n\n");

        int unmarkedCount = (unmarkedSessions != null) ? unmarkedSessions.length() : 0;
        output.append("Upcoming / Unmarked Sessions (").append(unmarkedCount).append("):\n\n");

        if (unmarkedSessions != null && unmarkedSessions.length() > 0) {
            for (int u = 0; u < unmarkedSessions.length(); u++) {
                JSONObject s = unmarkedSessions.optJSONObject(u);
                if (s != null) {
                    String date = s.optString("date", "");
                    String time = s.optString("time", "");
                    String status = s.optString("status", "UNMARKED");
                    String rawText = s.optString("rawText", "");

                    output.append("• ");
                    if (!date.isEmpty()) output.append(date);
                    if (!time.isEmpty()) {
                        if (!date.isEmpty()) output.append("  |  ");
                        output.append(time);
                    }
                    if (!date.isEmpty() || !time.isEmpty()) output.append("  |  ");
                    output.append(status);
                    if (date.isEmpty() && time.isEmpty() && !rawText.isEmpty()) {
                        output.append(rawText);
                    }
                    output.append("\n\n");
                }
            }
        }



        showResult(output.toString().trim());
    }

    /**
     * Safely decodes rawValue returned by evaluateJavascript().
     * Handles JSONObject directly, JSON strings, and double-encoded JSON strings.
     */
    private static JSONObject parseJsResult(String rawValue) {
        if (rawValue == null || rawValue.trim().isEmpty() || rawValue.equals("null")) {
            return null;
        }

        try {
            Object tokenerResult = new JSONTokener(rawValue).nextValue();
            if (tokenerResult instanceof JSONObject) {
                return (JSONObject) tokenerResult;
            } else if (tokenerResult instanceof String) {
                String str = (String) tokenerResult;
                Object innerResult = new JSONTokener(str).nextValue();
                if (innerResult instanceof JSONObject) {
                    return (JSONObject) innerResult;
                }
            }
        } catch (Exception e) {
            try {
                return new JSONObject(rawValue);
            } catch (Exception ignored) {
            }
        }

        return null;
    }

    // ============================================================
    // RESULT SCREEN
    // ============================================================

    private void showResult(String text) {
        webView.setVisibility(View.GONE);
        resultScroll.setVisibility(View.VISIBLE);
        resultText.setText(text);
    }
}