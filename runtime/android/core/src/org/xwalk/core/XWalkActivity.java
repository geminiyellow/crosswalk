// Copyright (c) 2014 Intel Corporation. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.xwalk.core;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DownloadManager;
import android.app.ProgressDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.DialogInterface.OnDismissListener;
import android.content.DialogInterface.OnShowListener;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import org.xwalk.core.XWalkLibraryInterface.DecompressionListener;
import org.xwalk.core.XWalkLibraryInterface.DownloadListener;
import org.xwalk.core.XWalkLibraryInterface.InitializationListener;

/**
 * <code>XWalkActivity</code> helps to execute all procedures for initializing the Crosswalk
 * environment, and displays dialogs for interacting with the end-user if necessary. The
 * activities that hold the {@link XWalkView} object might want to extend
 * <code>XWalkActivity</code> to obtain this capability. For those activities, it’s important
 * to override the abstract method {@link #onXWalkReady} that notifies the Crosswalk
 * environment is ready.
 *
 * <p>In shared mode, the Crosswalk runtime library is not loaded yet at the moment the
 * activity is created. So the developer can’t use embedding API in <code>onCreate()</code>
 * as usual. All routines using embedding API should be inside {@link #onXWalkReady} or after
 * {@link #onXWalkReady} is invoked.
 */
public abstract class XWalkActivity extends Activity {
    private static final String XWALK_APK_MARKET_URL = "market://details?id=org.xwalk.core";
    private static final String TAG = "XWalkActivity";

    private XWalkLibraryListener mLibraryListener;
    private Dialog mActiveDialog;
    private boolean mIsXWalkReady;
    private String mXWalkApkDownloadUrl;

    private static class XWalkLibraryListener
            implements DecompressionListener, DownloadListener, InitializationListener {
        XWalkActivity mXWalkActivity;

        XWalkLibraryListener(XWalkActivity activity) {
            mXWalkActivity = activity;
        }

        @Override
        public void onDecompressionStarted() {
            mXWalkActivity.showDialog(mXWalkActivity.getDecompressionProgressDialog());
        }

        @Override
        public void onDecompressionCancelled() {
            mXWalkActivity.dismissDialog();
            mXWalkActivity.finish();
        }

        @Override
        public void onDecompressionCompleted() {
            mXWalkActivity.dismissDialog();
            mXWalkActivity.initXWalkLibrary();
        }

        @Override
        public void onDownloadStarted() {
            mXWalkActivity.showDialog(mXWalkActivity.getDownloadProgressDialog());
        }

        @Override
        public void onDownloadUpdated(int percentage) {
            ProgressDialog dialog = (ProgressDialog) mXWalkActivity.mActiveDialog;
            dialog.setIndeterminate(false);
            dialog.setMax(100);
            dialog.setProgress(percentage);
        }

        @Override
        public void onDownloadCancelled() {
            mXWalkActivity.dismissDialog();
            mXWalkActivity.finish();
        }

        @Override
        public void onDownloadCompleted(Uri uri) {
            mXWalkActivity.dismissDialog();

            Log.d(TAG, "Install the Crosswalk library, " + uri.toString());
            Intent install = new Intent(Intent.ACTION_VIEW);
            install.setDataAndType(uri, "application/vnd.android.package-archive");
            mXWalkActivity.startActivity(install);
        }

        @Override
        public void onDownloadFailed(int status, int error) {
            mXWalkActivity.dismissDialog();

            String errMsg = null;
            if (status == DownloadManager.STATUS_FAILED) {
                if (error == DownloadManager.ERROR_DEVICE_NOT_FOUND) {
                    errMsg = mXWalkActivity.getString(R.string.download_failed_device_not_found) ;
                } else if (error == DownloadManager.ERROR_INSUFFICIENT_SPACE) {
                    errMsg = mXWalkActivity.getString(R.string.download_failed_insufficient_space);
                } else {
                    errMsg = mXWalkActivity.getString(R.string.download_failed_message);
                }
            } else if (status == DownloadManager.STATUS_PAUSED) {
                errMsg = mXWalkActivity.getString(R.string.download_failed_time_out);
            }

            AlertDialog dialog = mXWalkActivity.getDownloadFailedDialog();
            dialog.setMessage(errMsg);
            mXWalkActivity.showDialog(dialog);
        }

        @Override
        public void onInitializationStarted() {
        }

        @Override
        public void onInitializationCompleted() {
            mXWalkActivity.mIsXWalkReady = true;
            mXWalkActivity.onXWalkReady();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mLibraryListener = new XWalkLibraryListener(this);
        XWalkLibraryLoader.prepareToInit();
        XWalkLibraryLoader.startDecompression(mLibraryListener, this);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!mIsXWalkReady) initXWalkLibrary();
    }

    /**
     * Returns the Resource instance comes from the application context
     */
    @Override
    public Resources getResources() {
        return getApplicationContext().getResources();
    }

    /**
     * Returns true if the Crosswalk environment is ready, false otherwise
     */
    protected boolean isXWalkReady() {
        return mIsXWalkReady;
    }

    /**
     * This method will be invoked when the Crosswalk environment is ready
     */
    protected abstract void onXWalkReady();

    private void initXWalkLibrary() {
        int status = XWalkLibraryLoader.initXWalkLibrary(this);
        if (status == XWalkLibraryInterface.STATUS_MATCH) {
            if (mActiveDialog != null) dismissDialog();
            XWalkLibraryLoader.startInitialization(mLibraryListener);
            return;
        }

        if (mActiveDialog != null) return;

        if (status == XWalkLibraryInterface.STATUS_NOT_FOUND) {
            showDialog(getStartupNotFoundDialog());
        } else if (status == XWalkLibraryInterface.STATUS_ARCHITECTURE_MISMATCH) {
            showDialog(getStartupArchitectureMismatchDialog());
        } else if (status == XWalkLibraryInterface.STATUS_SIGNATURE_CHECK_ERROR) {
            showDialog(getStartupSignatureCheckErrorDialog());
        } else if (status == XWalkLibraryInterface.STATUS_OLDER_VERSION) {
            showDialog(getStartupOlderVersionDialog());
        } else if (status == XWalkLibraryInterface.STATUS_NEWER_VERSION) {
            showDialog(getStartupNewerVersionDialog());
        }
    }

    private void getXWalkLibrary() {
        // The download url is defined by the meta-data item with the name of "xwalk_apk_url"
        // under the application tag in AndroidManifest.xml. It can also be specified via
        // --xwalk-apk-url option of make_apk script indirectly.
        if (mXWalkApkDownloadUrl == null) {
            try {
                PackageManager packageManager = getPackageManager();
                ApplicationInfo appInfo = packageManager.getApplicationInfo(
                        getPackageName(), PackageManager.GET_META_DATA);
                if (appInfo.metaData != null) {
                    mXWalkApkDownloadUrl = appInfo.metaData.getString("xwalk_apk_url");
                }
            } catch (NameNotFoundException e) {
            }
            if (mXWalkApkDownloadUrl == null) mXWalkApkDownloadUrl = "";
            Log.d(TAG, "Crosswalk APK download URL: " + mXWalkApkDownloadUrl);
        }

        if (!mXWalkApkDownloadUrl.isEmpty()) {
            downloadXWalkLibrary();
        } else {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW);
                startActivity(intent.setData(Uri.parse(XWALK_APK_MARKET_URL)));
            } catch (ActivityNotFoundException e) {
                Log.d(TAG, "Market open failed");
                showDialog(getMarketOpenFailedDialog());
            }
        }
    }

    private void downloadXWalkLibrary() {
        XWalkLibraryLoader.startDownload(mLibraryListener, this, mXWalkApkDownloadUrl);
    }

    private void showDialog(Dialog dialog) {
        mActiveDialog = dialog;
        mActiveDialog.show();
    }

    private void dismissDialog() {
        mActiveDialog.dismiss();
        mActiveDialog = null;
    }

    private ProgressDialog buildProgressDialog() {
        ProgressDialog dialog = new ProgressDialog(this);
        dialog.setProgressStyle(ProgressDialog.STYLE_SPINNER);
        dialog.setIndeterminate(true);
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);
        return dialog;
    }

    private AlertDialog buildAlertDialog() {
        AlertDialog dialog = new AlertDialog.Builder(this).create();
        dialog.setIcon(android.R.drawable.ic_dialog_alert);
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);
        return dialog;
    }

    private ProgressDialog getDecompressionProgressDialog() {
        ProgressDialog dialog = buildProgressDialog();
        dialog.setMessage(getString(R.string.decompression_progress_message));
        dialog.setButton(DialogInterface.BUTTON_POSITIVE, getString(R.string.xwalk_cancel),
                new OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int id) {
                        XWalkLibraryLoader.cancelDecompression();
                    }
                });
        return dialog;
    }

    private ProgressDialog getDownloadProgressDialog() {
        ProgressDialog dialog = buildProgressDialog();
        dialog.setMessage(getString(R.string.download_progress_message));
        dialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        dialog.setButton(DialogInterface.BUTTON_NEGATIVE, getString(R.string.xwalk_cancel),
                new OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int id) {
                        XWalkLibraryLoader.cancelDownload();
                    }
                });
        return dialog;
    }

    private AlertDialog getDownloadFailedDialog() {
        AlertDialog dialog = buildAlertDialog();
        dialog.setTitle(getString(R.string.download_failed_title));
        dialog.setButton(DialogInterface.BUTTON_POSITIVE, getString(R.string.xwalk_retry),
                new OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int id) {
                        downloadXWalkLibrary();
                    }
                });
        dialog.setButton(DialogInterface.BUTTON_NEGATIVE, getString(R.string.xwalk_cancel),
                new OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int id) {
                        finish();
                    }
                });
        return dialog;
    }

    private AlertDialog getMarketOpenFailedDialog() {
        AlertDialog dialog = buildAlertDialog();
        dialog.setTitle(getString(R.string.market_open_failed_title));
        dialog.setMessage(getString(R.string.market_open_failed_message));

        dialog.setButton(DialogInterface.BUTTON_POSITIVE, getString(R.string.xwalk_cancel),
                new OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int id) {
                        finish();
                    }
                });
        return dialog;
    }

    private AlertDialog getStartupNotFoundDialog() {
        AlertDialog dialog = buildAlertDialog();
        dialog.setTitle(getString(R.string.startup_not_found_title));
        dialog.setMessage(getString(R.string.startup_not_found_message));
        dialog.setButton(DialogInterface.BUTTON_POSITIVE, getString(R.string.get_crosswalk),
                new OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int id) {
                        getXWalkLibrary();
                    }
                });
        dialog.setButton(DialogInterface.BUTTON_NEGATIVE, getString(R.string.xwalk_cancel),
                new OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int id) {
                        finish();
                    }
                });
        return dialog;
    }

    private AlertDialog getStartupArchitectureMismatchDialog() {
        AlertDialog dialog = buildAlertDialog();
        dialog.setTitle(getString(R.string.startup_architecture_mismatch_title));
        dialog.setMessage(getString(R.string.startup_architecture_mismatch_message));
        dialog.setButton(DialogInterface.BUTTON_POSITIVE, getString(R.string.get_crosswalk),
                new OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int id) {
                        getXWalkLibrary();
                    }
                });
        dialog.setButton(DialogInterface.BUTTON_NEGATIVE, getString(R.string.xwalk_cancel),
                new OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int id) {
                        finish();
                    }
                });
        return dialog;
    }

    private AlertDialog getStartupSignatureCheckErrorDialog() {
        AlertDialog dialog = buildAlertDialog();
        dialog.setTitle(getString(R.string.startup_signature_check_error_title));
        dialog.setMessage(getString(R.string.startup_signature_check_error_message));
        dialog.setButton(DialogInterface.BUTTON_NEGATIVE, getString(R.string.xwalk_cancel),
                new OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int id) {
                        finish();
                    }
                });
        return dialog;
    }

    private AlertDialog getStartupOlderVersionDialog() {
        AlertDialog dialog = buildAlertDialog();
        dialog.setTitle(getString(R.string.startup_older_version_title));
        dialog.setMessage(getString(R.string.startup_older_version_message));
        dialog.setButton(DialogInterface.BUTTON_POSITIVE, getString(R.string.get_crosswalk),
                new OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int id) {
                        getXWalkLibrary();
                    }
                });
        dialog.setButton(DialogInterface.BUTTON_NEGATIVE, getString(R.string.xwalk_cancel),
                new OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int id) {
                        finish();
                    }
                });
        return dialog;
    }

    private AlertDialog getStartupNewerVersionDialog() {
        AlertDialog dialog = buildAlertDialog();
        dialog.setTitle(getString(R.string.startup_newer_version_title));
        dialog.setMessage(getString(R.string.startup_newer_version_message));
        dialog.setButton(DialogInterface.BUTTON_POSITIVE, getString(R.string.xwalk_cancel),
                new OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int id) {
                        finish();
                    }
                });
        return dialog;
    }
}
