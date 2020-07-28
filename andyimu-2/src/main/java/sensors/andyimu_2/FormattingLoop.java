package sensors.andyimu_2;

import android.os.Handler;
import android.os.HandlerThread;

/**
 * This handler class creates an infinite loop (Looper) into which the runnable - FormattingRunnable
 * is posted. In other words, this thread helps in keeping on formatting the sensor data into
 * the form which will be understandable by the receiver. No other task would be posted in this loop
 * because the the formatting runnable needs to run continuously. This loop is helpful because data
 * sending (and hence formatting) would be started and stopped multiple times. Everytime an instance
 * of this class just posts the runnable here and work gets done in the background.
 */
class FormattingLoop extends HandlerThread {

    // The handler which posts on MessageQueue which the looper associated with this thread is maintaining
    private Handler handler;

    FormattingLoop() {
        super("FormatSensorOutput");
        start();                            // Starts the looper
        handler = new Handler(getLooper());
    }

    // Visible to the instance of class to post tasks onto this loop.
    void execute(Runnable task){
        handler.post(task);
    }

}
