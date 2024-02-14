package com.example.printsample;

import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.print.PrintAttributes;
import android.print.PrintAttributes.Margins;
import android.print.PrintAttributes.MediaSize;
import android.print.PrintAttributes.Resolution;
import android.print.PrintJobId;
import android.print.PrintJobInfo;
import android.print.PrinterCapabilitiesInfo;
import android.print.PrinterId;
import android.print.PrinterInfo;
import android.printservice.PrintJob;
import android.printservice.PrintService;
import android.printservice.PrinterDiscoverySession;
import android.util.ArrayMap;
import android.util.Log;

import androidx.annotation.RequiresApi;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class MyPrintService extends PrintService {
    private static final String LOG_TAG = "MyPrintService";
    static final String INTENT_EXTRA_ACTION_TYPE = "INTENT_EXTRA_ACTION_TYPE";
    static final String INTENT_EXTRA_PRINT_JOB_ID = "INTENT_EXTRA_PRINT_JOB_ID";
    static final int ACTION_TYPE_ON_PRINT_JOB_PENDING = 1;
    static final int ACTION_TYPE_ON_REQUEST_CANCEL_PRINT_JOB = 2;
    private AsyncTask<ParcelFileDescriptor, Void, Void> mFakePrintTask;

    private final Map<PrintJobId, PrintJob> mProcessedPrintJobs =
            new ArrayMap<PrintJobId, PrintJob>();

    @Override
    protected void onConnected() {
        Log.i(LOG_TAG, "#onConnected()");
    }

    @Override
    protected void onDisconnected() {
        Log.i(LOG_TAG, "#onDisconnected()");
    }

    @Override
    protected PrinterDiscoverySession onCreatePrinterDiscoverySession() {
        Log.i(LOG_TAG, "#onCreatePrinterDiscoverySession()");
        return new FakePrinterDiscoverySession();
    }

    @Override
    protected void onRequestCancelPrintJob(final PrintJob printJob) {
        Log.i(LOG_TAG, "#onRequestCancelPrintJob()");
        mProcessedPrintJobs.put(printJob.getId(), printJob);
        Intent intent = new Intent(this, MyDialogActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra(INTENT_EXTRA_PRINT_JOB_ID, printJob.getId());
        intent.putExtra(INTENT_EXTRA_ACTION_TYPE, ACTION_TYPE_ON_REQUEST_CANCEL_PRINT_JOB);
        startActivity(intent);
    }

    @Override
    public void onPrintJobQueued(final PrintJob printJob) {
        Log.i(LOG_TAG, "#onPrintJobQueued()");
        mProcessedPrintJobs.put(printJob.getId(), printJob);
        if (printJob.isQueued()) {
            printJob.start();
        }

        Intent intent = new Intent(this, MyDialogActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra(INTENT_EXTRA_PRINT_JOB_ID, printJob.getId());
        intent.putExtra(INTENT_EXTRA_ACTION_TYPE, ACTION_TYPE_ON_PRINT_JOB_PENDING);
        startActivity(intent);
    }

    void handleQueuedPrintJob(PrintJobId printJobId) {
        final PrintJob printJob = mProcessedPrintJobs.get(printJobId);
        if (printJob == null) {
            return;
        }

        if (printJob.isQueued()) {
            printJob.start();
        }

        final PrintJobInfo info = printJob.getInfo();
        final File file = new File(getFilesDir(), info.getLabel() + ".pdf");

        mFakePrintTask = new AsyncTask<ParcelFileDescriptor, Void, Void>() {
            @Override
            protected Void doInBackground(ParcelFileDescriptor... params) {
                InputStream in = new BufferedInputStream(new FileInputStream(
                        params[0].getFileDescriptor()));
                OutputStream out = null;
                try {
                    out = new BufferedOutputStream(new FileOutputStream(file));
                    final byte[] buffer = new byte[8192];
                    while (true) {
                        if (isCancelled()) {
                            break;
                        }
                        final int readByteCount = in.read(buffer);
                        if (readByteCount < 0) {
                            break;
                        }
                        out.write(buffer, 0, readByteCount);
                    }
                } catch (IOException ioe) {
                    throw new RuntimeException(ioe);
                } finally {
                    if (in != null) {
                        try {
                            in.close();
                        } catch (IOException ioe) {
                            /* ignore */
                        }
                    }
                    if (out != null) {
                        try {
                            out.close();
                        } catch (IOException ioe) {
                            /* ignore */
                        }
                    }
                    if (isCancelled()) {
                        file.delete();
                    }
                }
                return null;
            }

            @Override
            protected void onPostExecute(Void result) {
                if (printJob.isStarted()) {
                    printJob.complete();
                }

                file.setReadable(true, false);

                // Quick and dirty to show the file - use a content provider instead.
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(Uri.fromFile(file), "application/pdf");
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent, null);

                mFakePrintTask = null;
            }

            @Override
            protected void onCancelled(Void result) {
                if (printJob.isStarted()) {
                    printJob.cancel();
                }
            }
        };
        mFakePrintTask.executeOnExecutor(AsyncTask.SERIAL_EXECUTOR,
                printJob.getDocument().getData());
    }

    /*private final class MyHandler extends Handler {
        public static final int MSG_HANDLE_DO_PRINT_JOB = 1;

        public MyHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message message) {
            switch (message.what) {
                case MSG_HANDLE_DO_PRINT_JOB: {
                    PrintJobId printJobId = (PrintJobId) message.obj;
                    handleQueuedPrintJob(printJobId);
                }
                break;
            }
        }
    }*/

    private final class FakePrinterDiscoverySession extends PrinterDiscoverySession {
        private final Handler mSesionHandler = new SessionHandler(getMainLooper());

        private final List<PrinterInfo> mFakePrinters = new ArrayList<PrinterInfo>();

        public FakePrinterDiscoverySession() {
                Log.i(LOG_TAG, "FakePrinterDiscoverySession ");
                String name = "Sample printer";
                PrinterInfo printer = new PrinterInfo
                        .Builder(generatePrinterId(name), name, PrinterInfo.STATUS_BUSY)
                        .build();
                mFakePrinters.add(printer);
        }

        @Override
        public void onDestroy() {
            Log.i(LOG_TAG, "FakePrinterDiscoverySession#onDestroy()");
            mSesionHandler.removeMessages(SessionHandler.MSG_ADD_FIRST_BATCH_FAKE_PRINTERS);
        }

        @Override
        public void onStartPrinterDiscovery(List<PrinterId> priorityList) {
            Log.i(LOG_TAG, "FakePrinterDiscoverySession#onStartPrinterDiscovery()");
            Message message1 = mSesionHandler.obtainMessage(
                    SessionHandler.MSG_ADD_FIRST_BATCH_FAKE_PRINTERS, this);
            mSesionHandler.sendMessageDelayed(message1, 0);
        }

        @Override
        public void onStopPrinterDiscovery() {
            cancellAddingFakePrinters();
        }

        @Override
        public void onStartPrinterStateTracking(PrinterId printerId) {
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @RequiresApi(api = Build.VERSION_CODES.M)
                @Override
                public void run() {
                    PrinterInfo printer = findPrinterInfo(printerId);
                    if (printer != null) {
                        PrinterCapabilitiesInfo capabilities =
                                new PrinterCapabilitiesInfo.Builder(printerId)
                                        .setColorModes(PrintAttributes.COLOR_MODE_COLOR | PrintAttributes.COLOR_MODE_MONOCHROME,
                                                PrintAttributes.COLOR_MODE_MONOCHROME)
                                        .setMinMargins(new Margins(200, 1000, 200, 1000))
                                        .addMediaSize(MediaSize.ISO_A4.asPortrait(), false)
                                        .addMediaSize(MediaSize.ISO_A5.asPortrait(), true)
                                        .addResolution(new Resolution("R1", "200 * 200", 200, 200), false)
                                        .addResolution(new Resolution("R2", "500 * 500", 500, 500), true)
                                        .setDuplexModes(PrintAttributes.DUPLEX_MODE_NONE | PrintAttributes.DUPLEX_MODE_LONG_EDGE | PrintAttributes.DUPLEX_MODE_SHORT_EDGE, PrintAttributes.DUPLEX_MODE_LONG_EDGE)
                                        .build();

                        printer = new PrinterInfo.Builder(printer)
                                .setCapabilities(capabilities)
                                .build();

                        List<PrinterInfo> printers = new ArrayList<PrinterInfo>();
                        printers.add(printer);
                        addPrinters(printers);
                    }
                }
            }, 500);
        }

        @Override
        public void onValidatePrinters(List<PrinterId> printerIds) {
            Log.i(LOG_TAG, "FakePrinterDiscoverySession#onValidatePrinters()");
        }

        @Override
        public void onStopPrinterStateTracking(PrinterId printerId) {
            Log.i(LOG_TAG, "FakePrinterDiscoverySession#onStopPrinterStateTracking()");
        }

        private void addFirstBatchFakePrinters() {
            List<PrinterInfo> printers = mFakePrinters.subList(0, mFakePrinters.size());
            addPrinters(printers);
        }

        private PrinterInfo findPrinterInfo(PrinterId printerId) {
            List<PrinterInfo> printers = getPrinters();
            final int printerCount = getPrinters().size();
            for (int i = 0; i < printerCount; i++) {
                PrinterInfo printer = printers.get(i);
                if (printer.getId().equals(printerId)) {
                    return printer;
                }
            }
            return null;
        }

        private void cancellAddingFakePrinters() {
            mSesionHandler.removeMessages(SessionHandler.MSG_ADD_FIRST_BATCH_FAKE_PRINTERS);
        }

        final class SessionHandler extends Handler {
            public static final int MSG_ADD_FIRST_BATCH_FAKE_PRINTERS = 1;

            public SessionHandler(Looper looper) {
                super(looper);
            }

            @Override
            public void handleMessage(Message message) {
                switch (message.what) {
                    case MSG_ADD_FIRST_BATCH_FAKE_PRINTERS: {
                        addFirstBatchFakePrinters();
                    }
                    break;
                }
            }
        }
    }
}
