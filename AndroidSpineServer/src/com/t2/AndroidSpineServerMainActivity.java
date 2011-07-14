package com.t2;

import java.util.Timer;
import java.util.TimerTask;
import java.util.Vector;

import org.achartengine.ChartFactory;
import org.achartengine.GraphicalView;
import org.achartengine.model.XYMultipleSeriesDataset;
import org.achartengine.model.XYSeries;
import org.achartengine.renderer.XYMultipleSeriesRenderer;
import org.achartengine.renderer.XYSeriesRenderer;

import com.t2.SpineReceiver.BioFeedbackStatus;
import com.t2.SpineReceiver.OnBioFeedbackMessageRecievedListener;
import com.t2.biomap.BioLocation;
import com.t2.biomap.BioMapActivity;
import com.t2.Constants;

import spine.datamodel.Node;
import spine.SPINEFactory;
import spine.SPINEFunctionConstants;
import spine.SPINEListener;
import spine.SPINEManager;
import spine.datamodel.Address;
import spine.datamodel.Data;
import spine.datamodel.Feature;
import spine.datamodel.FeatureData;
import spine.datamodel.MindsetData;
import spine.datamodel.ServiceMessage;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.DialogInterface.OnCancelListener;
import android.content.DialogInterface.OnClickListener;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup.LayoutParams;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;

/**
 * Main (test) activity for Spine server.
 * This application sets up and initializes the Spine server to talk to a couple of sample devices
 * and displays their data streams.
 * 
 * Note that the AndroidBTService is an Android background service mandatory as a front end 
 * for the Bluetooth devices to work with the Spine server.
 * 
 * This activity uses two mechanisms to communicate with the AndroidBTService
 *  1. Broadcast intents are used to communicate low bandwidth messages: status messages and connection information
 *  2. A service connection is used to communicate potentially high bandwidth messages (Sensor data messages)
 * 
 * 
 * @author scott.coleman
 *
 */
public class AndroidSpineServerMainActivity extends Activity implements OnBioFeedbackMessageRecievedListener, SPINEListener {
	private static final String TAG = Constants.TAG;

    private static AndroidSpineConnector spineConnector;
    private static boolean firstTime = true;
    

	/**
     * The Spine manager contains the bulk of the Spine server. 
     */
    private static SPINEManager manager;

    /**
	 * This is a broadcast receiver. Note that this is used ONLY for command/status messages from the AndroidBTService
	 * All data from the service goes through the mail SPINE mechanism (received(Data data)).
	 */
	private SpineReceiver receiver;
	
	/**
	 * Dialog used to indicate that the AndroidBTService is trying to connect to a sensor node
	 */
	private AlertDialog connectingDialog;

	/**
	 * Static instance of this activity
	 */
	private static AndroidSpineServerMainActivity instance;

	/**
	 * Service connection used to communicate data messages with the AndroidBTService
	 */
	ServiceConnection mConnection;
	
	/**
	 * This is the MEssenger service which is used to communicate sensor data messages between the main activity 
	 * and the AndroidBTService
	 */
	private Messenger mService = null;	
	
	/**
	 * Whether or not the AndroidBTService is bound to this activity
	 */
	boolean mIsBound = false;
	
	String mTargetName = "";
	String mLastMeditation = "";
	String mLastAttention = "";
	String mLastSignalStrength = "";
	
	Vector<BioLocation> currentUsers;
	
	private static Timer mDataUpdateTimer;	
	
	
	// Charting stuff
	private final static int SPINE_CHART_SIZE = 20;
	
	private GraphicalView mDeviceChartView;
	private XYMultipleSeriesDataset mDeviceDataset = new XYMultipleSeriesDataset();
	private XYMultipleSeriesRenderer mDeviceRenderer = new XYMultipleSeriesRenderer();
	private XYSeries mMindsetAttentionSeries;
	private XYSeries mMindsetMeditationSeries;
	private XYSeries mCurrentSpineSeries;
	  
    static final int MSG_UNREGISTER_CLIENT = 2;	
	
	private EditText detailLog;
	
	int mSpineChartX = 0;
	int mAttentionChartX = 0;
	int mMeditationChartX = 0;
	
	String mPackageName = "";
	int mVersionCode;
	String mVersionName = "";
	Vector<String>  mDeviceDetailContent = new Vector<String>();
	
	
	
	/**
	 * Sets up messenger service which is used to communicate to the AndroidBTService
	 * @param mService
	 */
	public void setmService(Messenger mService) {
		this.mService = mService;
	}

	/**
	 * @return Static instance of this activity
	 */
	public static AndroidSpineServerMainActivity getInstance() 
	{
	   return instance;
	}
    
    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        instance = this;
        setRequestedOrientation (ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);        
        
        
        try {
			// Get target name if one was supplied
			Bundle bundle = getIntent().getExtras();
			mTargetName = bundle.getString("TARGET_NAME");
		} catch (Exception e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
			mTargetName = "";
			
		}
        
        if (mTargetName == null)
        	mTargetName = "";
        
        
        ImageView image = (ImageView) findViewById(R.id.targetImage);
        
        if (mTargetName.equalsIgnoreCase("Scott"))
            image.setImageResource(R.drawable.scott);        
        if (mTargetName.equalsIgnoreCase("dave"))
            image.setImageResource(R.drawable.dave);        
        if (mTargetName.equalsIgnoreCase("bob"))
            image.setImageResource(R.drawable.bob);        
        
        AndroidSpineConnector.setMainActivityInstance(instance);
        
        Resources resources = this.getResources();
        AssetManager assetManager = resources.getAssets();
        
        final Button discoveryButton = (Button) findViewById(R.id.button1);
//        discoveryButton.setOnClickListener(new View.OnClickListener() {
//            public void onClick(View v) {
////				manager.discoveryWsn();
//				
//            }
//        });        
        
        detailLog = (EditText) findViewById(R.id.detailLog);
        
		// Initialize SPINE by passing the fileName with the configuration properties
		try {
			manager = SPINEFactory.createSPINEManager("SPINETestApp.properties", resources);
		} catch (InstantiationException e) {
			Log.e(TAG, "Exception creating SPINE manager: " + e.toString());
			e.printStackTrace();
		}        
		
		// Since zepher is a static node we have to manually put it in the active node list
		// Note that the sensor id 0xfff1 (-15) is a reserved id for this particular sensor
		Node zepherNode = null;
		zepherNode = new Node(new Address("" + Constants.RESERVED_ADDRESS_ZEPHYR));
		manager.getActiveNodes().add(zepherNode);
		
		Node mindsetNode = null;
		mindsetNode = new Node(new Address("" + Constants.RESERVED_ADDRESS_MINDSET));
		manager.getActiveNodes().add(mindsetNode);
				
		// ... then we need to register a SPINEListener implementation to the SPINE manager instance
		// to receive sensor node data from the Spine server
		// (I register myself since I'm a SPINEListener implementation!)
		manager.addListener(this);	        
                
		// Create a broadcast receiver. Note that this is used ONLY for command messages from the service
		// All data from the service goes through the mail SPINE mechanism (received(Data data)).
		// See public void received(Data data)
        this.receiver = new SpineReceiver(this);
        
        // Create a connecting dialog.
        this.connectingDialog = new AlertDialog.Builder(this)
        	// Close the app if connecting was not finished.
	        .setOnCancelListener(new OnCancelListener() {
				@Override
				public void onCancel(DialogInterface dialog) {
					finish();
				}
			})
			// Allow the biofeedback device settings to be used.
			.setPositiveButton("BioFeedback Settings", new OnClickListener() {
				@Override
				public void onClick(DialogInterface dialog, int which) {
					startActivity(new Intent("com.t2.biofeedback.MANAGER"));
				}
			})
			.setMessage("Connecting...")
			.create();


        
   

        
        // Set up Device data chart
        if (mDeviceChartView == null) 
        {
          LinearLayout layout = (LinearLayout) findViewById(R.id.deviceChart);
          mDeviceChartView = ChartFactory.getLineChartView(this, mDeviceDataset, mDeviceRenderer);
          layout.addView(mDeviceChartView, new LayoutParams(LayoutParams.FILL_PARENT, LayoutParams.FILL_PARENT));
        }    
        mDeviceRenderer.setShowLabels(false);
        mDeviceRenderer.setMargins(new int[] {0,0,0,0});
        mDeviceRenderer.setShowAxes(true);
        mDeviceRenderer.setShowLegend(true);
        
        mDeviceRenderer.setZoomEnabled(false, false);
        mDeviceRenderer.setPanEnabled(false, false);
        mDeviceRenderer.setYAxisMin(0);
        mDeviceRenderer.setYAxisMax(255);
        
        mMindsetAttentionSeries = new XYSeries("Attention");
        mMindsetMeditationSeries = new XYSeries("Meditation");
        mCurrentSpineSeries = new XYSeries("Test Data");

        mDeviceDataset.addSeries(mMindsetAttentionSeries);
        mDeviceDataset.addSeries(mMindsetMeditationSeries);
        mDeviceDataset.addSeries(mCurrentSpineSeries);

        
        XYSeriesRenderer renderer = new XYSeriesRenderer();
        
        renderer = new XYSeriesRenderer();
        renderer.setColor(Color.GREEN); // White
        mDeviceRenderer.addSeriesRenderer(renderer);

        
        renderer = new XYSeriesRenderer();
        renderer.setColor(Color.YELLOW); // White
        mDeviceRenderer.addSeriesRenderer(renderer);
        
        renderer = new XYSeriesRenderer();
        renderer.setColor(Color.WHITE); // White
        mDeviceRenderer.addSeriesRenderer(renderer);
        
        
		try {
			PackageManager packageManager = this.getPackageManager();
			PackageInfo info = packageManager.getPackageInfo(this.getPackageName(), 0);			
			mPackageName = info.packageName;
			mVersionCode = info.versionCode;
			mVersionName = info.versionName;
			Log.i(TAG, "Spine server Test Application Version " + mVersionName);
		} 
		catch (NameNotFoundException e) {
			   	Log.e(TAG, e.toString());
		}
		currentUsers = Util.setupUsers();
		
		mDataUpdateTimer = new Timer();
		mDataUpdateTimer.schedule(new TimerTask() {
			@Override
			public void run() {
				TimerMethod();
			}

		}, 0, 1000);		
		
		
		if (firstTime) 
		{

			if (mDataUpdateTimer != null)
			{
				mDataUpdateTimer.purge();
		    	mDataUpdateTimer.cancel();
				
			}
			
			
			firstTime = false;
//			Intent i = new Intent(this, BioDetailActivity.class);
			Intent i = new Intent(this, BioMapActivity.class);
			this.startActivity(i);
		}
		
		manager.discoveryWsn();
		
		
		
		
    } // End onCreate(Bundle savedInstanceState)
    
    @Override
	protected void onDestroy() {
    	super.onDestroy();
    	this.sendBroadcast(new Intent("com.t2.biofeedback.service.STOP"));
    	this.unregisterReceiver(this.receiver);
    	
    	if (mDataUpdateTimer != null)
    	{
	    	mDataUpdateTimer.purge();
	    	mDataUpdateTimer.cancel();
    	}
	    	
    	doUnbindService();    	
	}

	@Override
	protected void onStart() {
		super.onStart();
		
		// Tell the AndroidBTService to start up
		this.sendBroadcast(new Intent("com.t2.biofeedback.service.START"));
		
		// Set up filter intents so we can receive broadcasts
		IntentFilter filter = new IntentFilter();
		filter.addAction("com.t2.biofeedback.service.status.BROADCAST");

		// These are only used when the AndroidBTService sends sensor data directly to this 
		// activity.
		// Currently all sensor data comesin throuugh the service connection that is set up
		// to the AndroidBTService.
		
		//filter.addAction("com.t2.biofeedback.service.spinedata.BROADCAST");
		//filter.addAction("com.t2.biofeedback.service.data.BROADCAST");
		//filter.addAction("com.t2.biofeedback.service.zephyrdata.BROADCAST");
		
		this.registerReceiver(this.receiver,filter);
		

		
        		
	}
    
	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		this.getMenuInflater().inflate(R.menu.main, menu);
		return true;
	}
	
	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch(item.getItemId()) {
		case R.id.settings:
			startActivity(new Intent("com.t2.biofeedback.MANAGER"));
			return true;
			
		case R.id.about:
			String content = "National Center for Telehealth and Technology (T2)\n\n";
			content += "Spine Server Test Application\n";
			content += "Version " + mVersionName;
			
			AlertDialog.Builder alert = new AlertDialog.Builder(this);
			
			alert.setTitle("About");
			alert.setMessage(content);	
			alert.show();			
			return true;

			
		case R.id.biomap:

	    	mDataUpdateTimer.purge();
	    	mDataUpdateTimer.cancel();

			Intent i = new Intent(this, BioMapActivity.class);
			this.startActivity(i);
			return true;

		default:
				return super.onOptionsItemSelected(item);
		}
	}
	
	/**
	 * This callback is called whenever the AndroidBTService sends us an indication that
	 * it is actively trying to establish a BT connection to one of the nodes.
	 * 
	 * @see com.t2.SpineReceiver.OnBioFeedbackMessageRecievedListener#onStatusReceived(com.t2.SpineReceiver.BioFeedbackStatus)
	 */
	@Override
	public void onStatusReceived(BioFeedbackStatus bfs) {
		if(bfs.messageId.equals("CONN_CONNECTING")) {
			Log.i(TAG, "Received command : CONN_CONNECTING" );		
			this.connectingDialog.show();
			
		} else if(bfs.messageId.equals("CONN_ANY_CONNECTED")) {
			Log.i(TAG, "Received command : CONN_ANY_CONNECTED" );		
			this.connectingDialog.hide();
		}
	}

	@Override
	public void newNodeDiscovered(Node newNode) {
	}

	@Override
	public void received(ServiceMessage msg) {
	}

	/**
	 * This is where we receive sensor data that comes through the actual
	 * Spine channel. 
	 * @param data		Generic Spine data packet. Should be cast to specifid data type indicated by data.getFunctionCode()
	 *
	 * @see spine.SPINEListener#received(spine.datamodel.Data)
	 */
	@Override
	public void received(Data data) {
		
		if (data != null)
		{
			switch (data.getFunctionCode()) {
			case SPINEFunctionConstants.FEATURE: {
				Node source = data.getNode();
				Feature[] feats = ((FeatureData)data).getFeatures();
				Feature firsFeat = feats[0];
				byte sensor = firsFeat.getSensorCode();
				byte featCode = firsFeat.getFeatureCode();
				int ch1Value = firsFeat.getCh1Value();
				String result = Integer.toString(ch1Value);
				
				// Look up the id of this view and update the owners data
				// that corresponds to this address
				for (BioLocation user: currentUsers)
				{
					if (user.mAddress == source.getPhysicalID().getAsInt())
					{
						user.mHeartRate = ch1Value;
					}
				}				
		    	
				if (mTargetName.equalsIgnoreCase("scott"))
				{
			    	if (mCurrentSpineSeries.getItemCount() > SPINE_CHART_SIZE)
					{
						mCurrentSpineSeries.remove(0);
					}

			    	mCurrentSpineSeries.add(mSpineChartX++, ch1Value);
					if (mDeviceChartView != null) {
			            mDeviceChartView.repaint();
			        }   			    	
				}
		        Log.i(TAG,"ch1Value= " + ch1Value);
				break;
			}				
			case SPINEFunctionConstants.ZEPHYR: {
				Node source = data.getNode();
				Feature[] feats = ((FeatureData)data).getFeatures();
				Feature firsFeat = feats[0];
				
				byte sensor = firsFeat.getSensorCode();
				byte featCode = firsFeat.getFeatureCode();
				int batLevel = firsFeat.getCh1Value();
				int heartRate = firsFeat.getCh2Value();
				double respRate = firsFeat.getCh3Value() / 10;
				int skinTemp = firsFeat.getCh4Value() / 10;
				double skinTempF = (skinTemp * 9 / 5) + 32;				
				Log.i(TAG,"heartRate= " + heartRate + ", respRate= " + respRate + ", skinTemp= " + skinTempF);
				
				if (mMindsetAttentionSeries.getItemCount() > SPINE_CHART_SIZE)
				{
					mMindsetAttentionSeries.remove(0);
				}

				mMindsetAttentionSeries.add(mSpineChartX++, heartRate);
		        if (mDeviceChartView != null) {
		            mDeviceChartView.repaint();
		        }        
				break;
			}
			
			case SPINEFunctionConstants.MINDSET: {
				Node source = data.getNode();
				
				MindsetData mData = (MindsetData) data;
				if (mData.exeCode == Constants.EXECODE_POOR_SIG_QUALITY)
				{
					Log.i(TAG, "poorSignalStrength= "  + mData.poorSignalStrength);
//					int b = mData.poorSignalStrength &  0xff;
//					String result = Integer.toHexString(b);					
					// Look up the id of this view and update the owners data
					// that corresponds to this address
					for (BioLocation user: currentUsers)
					{
						if (user.mAddress == source.getPhysicalID().getAsInt())
						{
							user.mSignalStrength = mData.poorSignalStrength;
						}
					}					
					
	
				}
				if (mData.exeCode == Constants.EXECODE_ATTENTION)
				{
					Log.i(TAG, "attention= "  + mData.attention);

					// Look up the id of this view and update the owners data
					// that corresponds to this address
					for (BioLocation user: currentUsers)
					{
						if (user.mAddress == source.getPhysicalID().getAsInt())
						{
							user.mAttention = mData.attention;
						}
					}					
					
					if (!mTargetName.equalsIgnoreCase("scott"))
					{
						if (mMindsetAttentionSeries.getItemCount() > SPINE_CHART_SIZE)
						{
							mMindsetAttentionSeries.remove(0);
						}
						
	//					mMindsetAttentionSeries.add(mAttentionChartX, mData.attention);
	//					mAttentionChartX++;
						
						mMindsetAttentionSeries.add(mSpineChartX, mData.attention);
						mSpineChartX++;
						
						if (mDeviceChartView != null) {
				            mDeviceChartView.repaint();
				        }
					}
				}
				if (mData.exeCode == Constants.EXECODE_MEDITATION)
				{
					Log.i(TAG, "meditation= "  + mData.meditation);
					
					// Look up the id of this view and update the owners data
					// that corresponds to this address
					for (BioLocation user: currentUsers)
					{
						if (user.mAddress == source.getPhysicalID().getAsInt())
						{
							user.mMeditation = mData.meditation;
						}
					}					

					if (!mTargetName.equalsIgnoreCase("scott"))
					{					
						if (mMindsetMeditationSeries.getItemCount() > SPINE_CHART_SIZE)
						{
							mMindsetMeditationSeries.remove(0);
						}
	//					mMindsetMeditationSeries.add(mMeditationChartX, mData.meditation);
	//					mMeditationChartX++;
						mMindsetMeditationSeries.add(mSpineChartX, mData.meditation);
						mSpineChartX++;
	
						
						if (mDeviceChartView != null) {
				            mDeviceChartView.repaint();
				        }
					}
				}
				break;
				
			}			
				case SPINEFunctionConstants.ONE_SHOT:
					Log.i(TAG, "SPINEFunctionConstants.ONE_SHOT"  );
					break;
					
				case SPINEFunctionConstants.ALARM:
					Log.i(TAG, "SPINEFunctionConstants.ALARM"  );
					break;
			} // End switch (data.getFunctionCode())
			
			String statusLine = "";
			for (BioLocation user: currentUsers)
			{
				if (user.mName.equalsIgnoreCase(mTargetName))
				{
					statusLine = user.buildStatusText();
						detailLog.setText(statusLine);					
					
				}
			}			
			
		} // End if (data != null)
	}
	
	@Override
	public void discoveryCompleted(Vector activeNodes) {
		Log.i(TAG, "discovery completed" );	
		
		Node curr = null;
		for (Object o: activeNodes)
		{
			curr = (Node)o;
			Log.i(TAG, o.toString());
		}
			
	}



	/**
	 * Converts a byte array to an integer
	 * @param bytes		Bytes to convert
	 * @return			Integer representaion of byte array
	 */
	public static int byteArrayToInt(byte[] bytes) {
		int val = 0;
		
		for(int i = 0; i < bytes.length; i++) {
			int n = (bytes[i] < 0 ? (int)bytes[i] + 256 : (int)bytes[i]) << (8 * i);
			val += n;
		}
		
		return val;
	}

	/**
	 * Binds this activity to a service using the service connection specified.
	 * 
	 * Note that it is the responsibility of the calling party (AndroidMessageServer) 
	 * to update this activities member variable, mService, when the connection to 
	 * the service is complete.
	 * 
	 * AndroidMessageServer can't do the bind by itself because it needs to be done 
	 * by an Android activity. Also, we do it here because the AndroidMessageServer
	 * doesn't know when we are destroyed. Here we know and can unbind the service.
	 * 
	 * The reason we don't simply move all of the binding here is that AndroidMessageServer
	 * needs to create it's own Messenger for the service connection.
	 *  
	 * @param mConnection	A previously established service connection
	 */
	public void doBindService(ServiceConnection mConnection ) {
		this.mConnection = mConnection; 
		Log.i(TAG, "*****************binding **************************");

		try {
			Intent intent2 = new Intent("com.t2.biofeedback.IBioFeedbackService");
			bindService(intent2, mConnection, Context.BIND_AUTO_CREATE);
			Log.i(TAG, "*****************binding SUCCESS**************************");
			
			mIsBound = true;
		} catch (Exception e) {
			Log.i(TAG, "*****************binding FAIL**************************");
			Log.e(TAG, e.toString());
		}
	}	

	/**
	 * Unbinds any service connection we may have
	 */
	void doUnbindService() {
	    if (mIsBound) {
			Log.i(TAG, "*****************UN-binding **************************");
	    	
	        // If we have received the service, and hence registered with
	        // it, then now is the time to unregister.
	        if (mService != null) {
	            try {
	                Message msg = Message.obtain(null,MSG_UNREGISTER_CLIENT);
	    			Log.i(TAG, "*****************UN- binding SUCCESS**************************");
	    			// msg.replyTo = mMessenger; We don't care about reply to because we're shutting down
	                mService.send(msg);
	            } catch (RemoteException e) {
	                // There is nothing special we need to do if the service
	                // has crashed.
	            }
	        }

	        // Detach our existing connection.
	        unbindService(mConnection);
	        mIsBound = false;
	    }
	}
	
	void updateDetailLog(String text, int dataType)
	{
		
		if (mTargetName.equalsIgnoreCase("scott"))
		{
			if (dataType == Constants.DATA_TYPE_HEARTRATE)
				detailLog.setText("Heart Rate: " + text);
				
		}
		else
		{
			if (dataType == Constants.DATA_TYPE_MEDITATION)
				mLastMeditation = text;
			if (dataType == Constants.DATA_TYPE_ATTENTION)
				mLastAttention = text;
			if (dataType == Constants.DATA_SIGNAL_STRENGTH)
				mLastSignalStrength = text;
			
						
			
			detailLog.setText(	"Signal Strength: " + mLastSignalStrength + "\n" + 
								"Meditation: " + mLastMeditation + "\n" + 
								"Attention: " + mLastAttention);
			
		}
//		if (mDeviceDetailContent.size() > 4)
//			mDeviceDetailContent.remove(0);
//		mDeviceDetailContent.add(text);
//		String buf = new String();;
//		
//		int i = 0;
//		for (String entry: mDeviceDetailContent )
//		{
//	    	buf = buf + mDeviceDetailContent.elementAt(i++) + "\n";			
//			
//		}
//		detailLog.setText(buf);			
		
	}
	void startBiomap()
	{
		
	}

	public void T2ButtonHandler(View target)
	{
		Intent i = new Intent(this, BioMapActivity.class);
		this.startActivity(i);
		
	}
	
	private void TimerMethod()
	{
		//This method is called directly by the timer
		//and runs in the same thread as the timer.

		//We call the method that will work with the UI
		//through the runOnUiThread method.
		this.runOnUiThread(Timer_Tick);
	}

	private Runnable Timer_Tick = new Runnable() {
		public void run() {

		//This method runs in the same thread as the UI.    	       

		//Do something to the UI thread here
			Log.i(TAG, "Spine server Test Application Version " + mVersionName);
			

		}
	};



	@Override
	protected void onPause() {
		Log.i(TAG, "Spine server Test Application Version " + mVersionName);
		// TODO Auto-generated method stub
		super.onPause();
	}

	@Override
	protected void onStop() {
		Log.i(TAG, "Spine server Test Application Version " + mVersionName);
		// TODO Auto-generated method stub
		super.onStop();
	}	
	
	
}