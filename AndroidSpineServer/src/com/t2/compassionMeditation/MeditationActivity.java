package com.t2.compassionMeditation;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.sql.SQLException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;
import java.util.Vector;

import org.achartengine.model.XYSeries;

import com.j256.ormlite.android.apptools.OrmLiteBaseActivity;
import com.j256.ormlite.dao.Dao;
import com.j256.ormlite.stmt.QueryBuilder;

import com.t2.SpineReceiver;
import com.t2.SpineReceiver.BioFeedbackStatus;
import com.t2.SpineReceiver.OnBioFeedbackMessageRecievedListener;
import com.t2.biomap.LogNoteActivity;
import com.t2.biomap.SharedPref;
import com.t2.compassionMeditation.CompassionActivity.GraphKeyItem;

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
import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.os.Bundle;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;


//Need the following import to get access to the app resources, since this
//class is in a sub-package.
import com.t2.R;


public class MeditationActivity extends OrmLiteBaseActivity<DatabaseHelper>
		implements 	OnBioFeedbackMessageRecievedListener, SPINEListener, 
					View.OnTouchListener, SeekBar.OnSeekBarChangeListener {
	private static final String TAG = "MeditationActivity";
	private static final String mActivityVersion = "2.2";

	private int mIntroFade = 255;
	private int mSubTimerClick = 100;
	
	Dao<BioUser, Integer> mBioUserDao;
	Dao<BioSession, Integer> mBioSessionDao;

	BioUser mCurrentBioUser = null;
	BioSession mCurrentBioSession = null;
	List<BioUser> currentUsers;	
	

	/**
	 * Number of seconds remaining in the session
	 *   This is set initially from SharedPref.PREF_SESSION_LENGTH
	 */
	private int mSecondsRemaining = 0;
	
	/**
	 * Application version info determined by the package manager
	 */
	private String mApplicationVersion = "";

	/**
     * The Spine manager contains the bulk of the Spine server. 
     */
    private static SPINEManager mManager;

    /**
	 * This is a broadcast receiver. Note that this is used ONLY for command/status messages from the AndroidBTService
	 * All data from the service goes through the mail SPINE mechanism (received(Data data)).
	 */
	private SpineReceiver mCommandReceiver;
	
	/**
	 * Static instance of this activity
	 */
	private static MeditationActivity instance;
	
    /**
     * Toggled by screen press, indicates whether or not to show buttons/tools on screen
     */
    private boolean mShowingControls = false; 
	
	/**
	 * Timer for updating the UI
	 */
	private static Timer mDataUpdateTimer;	
	
	
	private BufferedWriter mLogWriter = null;
	private boolean mLoggingEnabled = false;
	private boolean mPaused = false;
	
	
	// UI Elements
	private Button mToggleLogButton;
    private Button mLlogMarkerButton;
    private Button mPauseButton;
//    private Button mBackButton;
    private TextView mTextInfoView;
    private TextView mTextBioHarnessView;
    private TextView mCountdownTextView;
    private ImageView mBuddahImage; 
    private ImageView mLotusImage; 
    private SeekBar mSeekBar;
    private ImageView mSignalImage;    
    /**
     * Moving average used to smooth the display of the band of interest
     */
    private MovingAverage mMovingAverage;
    private int mMovingAverageSize = 30;

    private MovingAverage mMovingAverageROC;
    private int mMovingAverageSizeROC = 6;

    /**
     * Gain used to determine how band of interest affects the buddah image 
     */
    private double mAlphaGain = 1;
    
	protected SharedPreferences sharedPref;
	MindsetData currentMindsetData;
	
	
	private int mMindsetBandOfInterest = MindsetData.THETA_ID; // Default to theta
	private int mBioHarnessParameterOfInterest = com.t2.compassionMeditation.Constants.PREF_BIOHARNESS_PARAMETER_OF_INTEREST_DEFAULT;
	private int numSecsWithoutData = 0;
	
	private static Object mKeysLock = new Object();
    private RateOfChange mRateOfChange;
    private int mRateOfChangeSize = 6;

	int mLotusRawValue = 0;;     
	double mLotusScaledValue = 0;;     
	int mLotusFilteredValue = 0;;     
	
	
	/**
	 * Temp variable used in SelectUser() to indicate which user was selected
	 *  Note that this needed to be a member variable because of error: 
	 *  	"Cannot refer to a non-final variable mSelection inside an inner 
	 *      class defined in a different method" 
	 */
	private int mSelection = 0;

	/**
	 * Session name which is used for file creation (based on selected user) 
	 */
	private String mSessionName = "";
	private String mLogCatName = "";
	
	boolean mSaveRawWave;
	boolean mAllowComments;
	boolean mShowAGain;
	String[] mBioHarnessParameters;	

	
	
    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        
                
		// Clear the logcat
        try {
		    String cmd = "logcat -c ";
		    Runtime.getRuntime().exec(cmd);
		} catch (IOException e) {
			Log.e(TAG, "Error clearing logcat" + e.toString());
			e.printStackTrace();
		}			
        
//		try {
//			Account[] accounts = AccountManager.get(this).getAccounts();
//			for (Account account : accounts) {
//			  // TODO: Check possibleEmail against an email regex or treat
//			  // account.name as an email address only for certain account.type values.
//			  String possibleEmail = account.name;
//
//			}
//		} catch (Exception e1) {
//			Log.e(TAG, "Error Looking for accounts" + e1.toString());
//			e1.printStackTrace();
//		}		
//        
		
		Log.i(TAG, TAG +  " onCreate");
		instance = this;
        mRateOfChange = new RateOfChange(mRateOfChangeSize);
		
		mIntroFade = 255;
        
        // We don't want the screen to timeout in this activity
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        this.requestWindowFeature(Window.FEATURE_NO_TITLE);		// This needs to happen BEFORE setContentView
        
        
        setContentView(R.layout.meditation);
        
        sharedPref = PreferenceManager.getDefaultSharedPreferences(getBaseContext());   

    	currentMindsetData = new MindsetData(this);
		mSaveRawWave = SharedPref.getBoolean(this, 
				com.t2.compassionMeditation.Constants.PREF_SAVE_RAW_WAVE, 
				com.t2.compassionMeditation.Constants.PREF_SAVE_RAW_WAVE_DEFAULT);
		
		mShowAGain = SharedPref.getBoolean(this, 
				com.t2.compassionMeditation.Constants.PREF_SHOW_A_GAIN, 
				com.t2.compassionMeditation.Constants.PREF_SHOW_A_GAIN_DEFAULT);

		mAllowComments = SharedPref.getBoolean(this, 
				com.t2.compassionMeditation.Constants.PREF_COMMENTS, 
				com.t2.compassionMeditation.Constants.PREF_COMMENTS_DEFAULT);

		mBioHarnessParameters = getResources().getStringArray(R.array.bioharness_parameters_array);
		
        setRequestedOrientation (ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);       
        
        mSecondsRemaining = SharedPref.getInt(this, com.t2.compassionMeditation.Constants.PREF_SESSION_LENGTH, 	10);  
		

		mAlphaGain = SharedPref.getFloat(this, 
				com.t2.compassionMeditation.Constants.PREF_ALPHA_GAIN, 	
				com.t2.compassionMeditation.Constants.PREF_ALPHA_GAIN_DEFAULT);

        
        mMovingAverage = new MovingAverage(mMovingAverageSize);
        mMovingAverageROC = new MovingAverage(mMovingAverageSizeROC);
        
        View v1 = findViewById (R.id.buddahView); 
        v1.setOnTouchListener (this);        
        
        Resources resources = this.getResources();
        AssetManager assetManager = resources.getAssets();
        
        // Set up member variables to UI Elements
        mToggleLogButton = (Button) findViewById(R.id.buttonLogging);
        mLlogMarkerButton = (Button) findViewById(R.id.LogMarkerButton);
        mTextInfoView = (TextView) findViewById(R.id.textViewInfo);
        mTextBioHarnessView = (TextView) findViewById(R.id.textViewBioHarness);
        mCountdownTextView = (TextView) findViewById(R.id.countdownTextView);
        mPauseButton = (Button) findViewById(R.id.buttonPause);
//        mBackButton = (Button) findViewById(R.id.buttonBack);
        mSignalImage = (ImageView) findViewById(R.id.imageView1);    
                

        // Note that the seek bar is a debug thing - used only to set the
        // alpha of the buddah image manually for visual testing
        mSeekBar = (SeekBar)findViewById(R.id.seekBar1);
		mSeekBar.setOnSeekBarChangeListener(this);
		mSeekBar.setProgress((int) mAlphaGain * 10);      
		
        // Controls start as invisible, need to touch screen to activate them
		mCountdownTextView.setVisibility(View.INVISIBLE);
		mTextInfoView.setVisibility(View.INVISIBLE);
		mTextBioHarnessView.setVisibility(View.INVISIBLE);		
		mPauseButton.setVisibility(View.INVISIBLE);
//		mBackButton.setVisibility(View.INVISIBLE);
		mSeekBar.setVisibility(View.INVISIBLE);
		
        ImageView image = (ImageView) findViewById(R.id.imageView1);
        image.setImageResource(R.drawable.signal_bars0);  
        
        mBuddahImage = (ImageView) findViewById(R.id.buddahView);
        mBuddahImage.setImageResource(R.drawable.buddha);

        mLotusImage = (ImageView) findViewById(R.id.lotusView);
        mLotusImage.setImageResource(R.drawable.lotus_flower);
        

		// Initialize SPINE by passing the fileName with the configuration properties
		try {
			mManager = SPINEFactory.createSPINEManager("SPINETestApp.properties", resources);
		} catch (InstantiationException e) {
			Log.e(TAG, "Exception creating SPINE manager: " + e.toString());
			e.printStackTrace();
		}        
		
		// Since Mindset is a static node we have to manually put it in the active node list
		// Note that the sensor id 0xfff1 (-15) is a reserved id for this particular sensor
		Node mindsetNode = null;
		mindsetNode = new Node(new Address("" + Constants.RESERVED_ADDRESS_MINDSET));
		mManager.getActiveNodes().add(mindsetNode);
			
		
		Node zepherNode = null;
		zepherNode = new Node(new Address("" + Constants.RESERVED_ADDRESS_ZEPHYR));
		mManager.getActiveNodes().add(zepherNode);
		
		
                
		// Create a broadcast receiver. Note that this is used ONLY for command messages from the service
		// All data from the service goes through the mail SPINE mechanism (received(Data data)).
		// See public void received(Data data)
        this.mCommandReceiver = new SpineReceiver(this);
        
		try {
			PackageManager packageManager = this.getPackageManager();
			PackageInfo info = packageManager.getPackageInfo(this.getPackageName(), 0);			
			mApplicationVersion = info.versionName;
			Log.i(TAG, "Compassion Meditation Application Version: " + mApplicationVersion + ", Activity Version: " + mActivityVersion);
		} 
		catch (NameNotFoundException e) {
			   	Log.e(TAG, e.toString());
		}
		
		mManager.discoveryWsn();
		
		String selectedUserName = SharedPref.getString(this, "SelectedUser", 	"");
		
		// Now get the database object associated with this user
		
		try {
			mBioUserDao = getHelper().getBioUserDao();
			mBioSessionDao = getHelper().getBioSessionDao();
			
			QueryBuilder<BioUser, Integer> builder = mBioUserDao.queryBuilder();
			builder.where().eq(BioUser.NAME_FIELD_NAME, selectedUserName);
			builder.limit(1);
//			builder.orderBy(ClickCount.DATE_FIELD_NAME, false).limit(30);
			List<BioUser> list = mBioUserDao.query(builder.prepare());	
			
			if (list.size() == 1) {
				mCurrentBioUser = list.get(0);
			}
			else if (list.size() == 0)
			{
				try {
					mCurrentBioUser = new BioUser(selectedUserName, System.currentTimeMillis());
					mBioUserDao.create(mCurrentBioUser);
				} catch (SQLException e1) {
					Log.e(TAG, "Error creating user " + selectedUserName , e1);
				}		
			}
			else {
				Log.e(TAG, "General Database error" + selectedUserName);
			}
			
		} catch (SQLException e) {
			Log.e(TAG, "Can't find user: " + selectedUserName , e);

		}
		
		// Create a sessioin data point for this session (to put in data
//		sessionDataPoint = new SessionDataPoint(System.currentTimeMillis(),0);
		mCurrentBioSession = new BioSession(mCurrentBioUser, System.currentTimeMillis());
		
		
		// Create a log file name from the seledcted user and date/time
		SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US);
		String currentDateTimeString = sdf.format(new Date());
		
		mSessionName = selectedUserName + "_" + currentDateTimeString + ".log";
		mLogCatName = "Logcat" + currentDateTimeString + ".log";		
		

        
		
		
    	saveState();
    } // End onCreate(Bundle savedInstanceState)
    
    
    
    @Override
	public void onBackPressed() {
    	handlePause("Session Complete"); // Allow opportinuty for a note
		
   // 	super.onBackPressed();
	}



	@Override
	protected void onDestroy() {
    	super.onDestroy();
		Log.i(TAG, TAG +  " onDestroy");
    	
    	mLoggingEnabled = false;
    	try {
        	if (mLogWriter != null)
        		mLogWriter.close();
		} catch (IOException e) {
			Log.e(TAG, "Exeption closing file " + e.toString());
			e.printStackTrace();
		}        	
    	
    	this.unregisterReceiver(this.mCommandReceiver);
	}
    
	@Override
	protected void onStart() {
		super.onStart();
		Log.i(TAG, TAG +  " OnStart");
		
		
		// Set up filter intents so we can receive broadcasts
		IntentFilter filter = new IntentFilter();
		filter.addAction("com.t2.biofeedback.service.status.BROADCAST");
		this.registerReceiver(this.mCommandReceiver,filter);
		
		// Set up a timer to do graphical updates
		mDataUpdateTimer = new Timer();
		mDataUpdateTimer.schedule(new TimerTask() {
			@Override
			public void run() {
				TimerMethod();
			}

		}, 0, 10);		
		
		
	}
    
	/**
	 * Convert seconds to string display of hours:minutes:seconds 
	 * @param time Total number of seconds to display
	 * @return String formated to hours:minutes:seconds
	 */
	String secsToHMS(long time) {
		
		long secs = time;
		long hours = secs / 3600;
		secs = secs % 3600;
		long mins = secs / 60;
		secs = secs % 60;
		
		return "Time remaining: " + hours + ":" + mins + ":" + secs;
	}
	
	
	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
	//	this.getMenuInflater().inflate(R.menu.menu_compassion_meditation, menu);
		return true;
	}
	
	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch(item.getItemId()) {
		case R.id.settings:
			startActivity(new Intent("com.t2.biofeedback.MANAGER"));
			return true;
			
		case R.id.discover:
			mManager.discoveryWsn();

			return true;
			
		case R.id.about:
			String content = "National Center for Telehealth and Technology (T2)\n\n";
			content += "Compassion Meditation Application\n";
			content += "Application Version: " + mApplicationVersion + "\n";
			content += "Activity Version: " + mActivityVersion;
			
			AlertDialog.Builder alert = new AlertDialog.Builder(this);
			
			alert.setTitle("About");
			alert.setMessage(content);	
			alert.show();			
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
			Toast.makeText (getApplicationContext(), "**** Connecting to Sensor Node ****", Toast.LENGTH_SHORT).show ();
		} 
		else if(bfs.messageId.equals("CONN_ANY_CONNECTED")) {
			Log.i(TAG, "Received command : CONN_ANY_CONNECTED" );
			// Something has connected - discover what it was
			mManager.discoveryWsn();
			Toast.makeText (getApplicationContext(), "**** Sensor Node Connected ****", Toast.LENGTH_SHORT).show ();
		} 
		else if(bfs.messageId.equals("CONN_CONNECTION_LOST")) {
			Log.i(TAG, "Received command : CONN_ANY_CONNECTED" );		
			Toast.makeText (getApplicationContext(), "**** Sensor Node Connection lost ****", Toast.LENGTH_SHORT).show ();
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
		
		if (data != null) {
			switch (data.getFunctionCode()) {
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
				double mSkinTempF = (skinTemp * 9 / 5) + 32;				

				Log.i("SensorData","heartRate= " + heartRate + ", respRate= " + respRate + ", skinTemp= " + mSkinTempF);
				
				numSecsWithoutData = 0;		
	        	synchronized(mKeysLock) {
	        		switch (mBioHarnessParameterOfInterest) {
	        		case com.t2.compassionMeditation.Constants.PREF_BIOHARNESS_PSKINTEMP:
	    				// Skin temp scaling -  absolute range  0  - 110, practical range 70 - 110, alpha range		0 - 255
	        			mLotusRawValue = (int) mSkinTempF;
		        		mLotusScaledValue = mSkinTempF - 70;
	    				if (mLotusScaledValue < 0) mLotusScaledValue = 0;
	    				mLotusScaledValue *= (255F / (110F - 70F)); //  6.375
	    				if (mLotusScaledValue > 255) mLotusScaledValue = 255;
	        			break;
	        			
	        		case com.t2.compassionMeditation.Constants.PREF_BIOHARNESS_PHEARTRATE:
	    				// Heart rate scaling - absolute range  0  - 250, practical range 20 - 250, alpha range		0 - 255
	        			mLotusRawValue = heartRate;
		        		mLotusScaledValue = heartRate - 20;
	    				if (mLotusScaledValue < 0) mLotusScaledValue = 0;
	    				mLotusScaledValue *= (255F / (250 - 20F));
	    				if (mLotusScaledValue > 255) mLotusScaledValue = 255;
	        			break;
	        			
	        		case com.t2.compassionMeditation.Constants.PREF_BIOHARNESS_PRESPRATE:
	    				// Resp Rate - absolute range  0  - 120, practical range 5 - 120, alpha range		0 - 255
	        			mLotusRawValue = (int) respRate;
		        		mLotusScaledValue = respRate - 5;
	    				if (mLotusScaledValue < 0) mLotusScaledValue = 0;
	    				mLotusScaledValue *= (255F / (120 - 5F));
	    				if (mLotusScaledValue > 255) mLotusScaledValue = 255;
	        			break;

	        		default:
	        			mLotusRawValue = 0;
	        			mLotusScaledValue = 0;
	        			
	        		}
	        	}

				break;
			} // End case SPINEFunctionConstants.ZEPHYR:			
			

			case SPINEFunctionConstants.MINDSET: {
					Node source = data.getNode();
				
					MindsetData mindsetData = (MindsetData) data;
					//Log.i("BFDemo", "" + mindsetData.exeCode);
					if (mindsetData.exeCode == Constants.EXECODE_RAW_ACCUM) {
					}
					
					if (mindsetData.exeCode == Constants.EXECODE_POOR_SIG_QUALITY) {
						
			        	synchronized(mKeysLock) {	
			        		currentMindsetData.poorSignalStrength = mindsetData.poorSignalStrength;
			        	}

						int sigQuality = mindsetData.poorSignalStrength & 0xff;

						if (mShowingControls || sigQuality == 200)
							mSignalImage.setVisibility(View.VISIBLE);
						else
							mSignalImage.setVisibility(View.INVISIBLE);
						
						if (sigQuality == 200)
							mSignalImage.setImageResource(R.drawable.signal_bars0);
						else if (sigQuality > 150)
							mSignalImage.setImageResource(R.drawable.signal_bars1);
						else if (sigQuality > 100)
							mSignalImage.setImageResource(R.drawable.signal_bars2);
						else if (sigQuality > 50)
							mSignalImage.setImageResource(R.drawable.signal_bars3);
						else if (sigQuality > 25)
							mSignalImage.setImageResource(R.drawable.signal_bars4);
						else 
							mSignalImage.setImageResource(R.drawable.signal_bars5);

					}
					
					if (mindsetData.exeCode == Constants.EXECODE_SPECTRAL || mindsetData.exeCode == Constants.EXECODE_RAW_ACCUM) {
						Log.i(TAG, "Spectral Data");
			        	synchronized(mKeysLock) {	
							if (mPaused == false) {
								if (mindsetData.exeCode == Constants.EXECODE_RAW_ACCUM)
									currentMindsetData.updateRawWave(mindsetData);
	
								currentMindsetData.updateSpectral(mindsetData);
								numSecsWithoutData = 0;				
								Log.i("SensorData", ", " + currentMindsetData.getLogDataLine());
								
	
								if (mLoggingEnabled == true) {
									SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
									
									String currentDateTimeString = DateFormat.getDateInstance().format(new Date());				
									currentDateTimeString = sdf.format(new Date());
									
									String logData = currentDateTimeString + ",, " + currentMindsetData.getLogDataLine(mindsetData.exeCode, mSaveRawWave) + "\n";
									
							        try {
							        	if (mLogWriter != null)
							        		mLogWriter.write(logData);
									} catch (IOException e) {
										Log.e(TAG, e.toString());
									}
								}			
							} // End if (mPaused == false)
			        	}
					}
					
					if (mindsetData.exeCode == Constants.EXECODE_ATTENTION) {
						currentMindsetData.attention= mindsetData.attention;
					}
					
					if (mindsetData.exeCode == Constants.EXECODE_MEDITATION) {						
						currentMindsetData.meditation= mindsetData.meditation;
					}						
					
					break;
				} // End case SPINEFunctionConstants.MINDSET:
			} // End switch (data.getFunctionCode())
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
	 * Hansles UI button clicks
	 * @param v
	 */
	public void onButtonClick(View v)
	{
		 final int id = v.getId();
		    switch (id) {
		    case R.id.buttonBack:
		    	finish();
		    	break;
		    		    
		    case R.id.buttonPause:
		    	handlePause(mSessionName + " Paused");
		    	break;
		        
		    case R.id.buttonLogging:
		        if (mLoggingEnabled == true)
		        {
		        	mLoggingEnabled = false;
		        	mToggleLogButton.setText("Log:\nOFF");
		        	mToggleLogButton.getBackground().setColorFilter(Color.LTGRAY, PorterDuff.Mode.MULTIPLY);
		        	mLlogMarkerButton.setVisibility(View.INVISIBLE);

		        	try {
		            	if (mLogWriter != null)
		            		mLogWriter.close();
		    		} catch (IOException e) {
		    			Log.e(TAG, "Exeption closing file " + e.toString());
		    			e.printStackTrace();
		    		}        	
		        }
		        else
		        {
		        	mLoggingEnabled = true;
		        	mToggleLogButton.setText("Log:\nON");
		        	mToggleLogButton.getBackground().setColorFilter(0xFF00FF00, PorterDuff.Mode.MULTIPLY);
		        	mLlogMarkerButton.setVisibility(View.VISIBLE);
		        }
		    	break;

		    } // End switch		
	}
	
	/**
	 * This method is called directly by the timer and runs in the same thread as the timer
	 * From here We call the method that will work with the UI through the runOnUiThread method.
	 */
	private void TimerMethod()
	{
		this.runOnUiThread(Timer_Tick);
	}

	/**
	 * This method runs in the same thread as the UI.
	 */
	private Runnable Timer_Tick = new Runnable() {
		public void run() {

			if (mPaused == true || currentMindsetData == null) {
				return;
			}

			if (mSubTimerClick-- > 0) {
				if (mIntroFade > 0) {

					mBuddahImage.setAlpha(mIntroFade--);
					mLotusImage.setAlpha(mIntroFade--);
					
				}
				return;
			}
			else {
				mSubTimerClick = 100;
				
			}
			
			numSecsWithoutData++;

			
			// Update buddah image based on band of interest
			int rawBuddahValue = currentMindsetData.getFeatureValue(mMindsetBandOfInterest);
			mMovingAverage.pushValue(rawBuddahValue);	
			int filteredBuddahValue = (int) (mMovingAverage.getValue());
			String mindsetBandName = currentMindsetData.getSpectralName(mMindsetBandOfInterest); 
				

			// Set values for buddah from mindset data
			double  buddahAlphaValue = mAlphaGain * (double) filteredBuddahValue; 
			int iBuddahAlphaValue = (int) buddahAlphaValue;
			if (iBuddahAlphaValue > 255) iBuddahAlphaValue = 255; 
			
			mTextInfoView.setText(mindsetBandName + ": " + rawBuddahValue + ", " + filteredBuddahValue +  ", " + iBuddahAlphaValue);		
//			mTextInfoView.setText(mindsetBandName + ": " + rawBuddahValue + ", " + filteredBuddahValue +  ", " + iBuddahAlphaValue + ": " + (int) mAlphaGain);		
//			mTextInfoView.setText(bandName + ": " + filteredValue );		

			
				
			// Set values for the lotus from BioHarness data
			mRateOfChange.pushValue((float) mLotusScaledValue);	
			int filteredLotusValue = (int) (mRateOfChange.getValue() * 10);
			if (filteredLotusValue > 255) filteredLotusValue = 255;
			
			int iLotusAlphaValue = 255 - filteredLotusValue;
			
			String bioHarnessBandName = mBioHarnessParameters[mBioHarnessParameterOfInterest]; 
//			mTextBioHarnessView.setText(bioHarnessBandName + ": " + mLotusRawValue + ", " + (int) mLotusScaledValue+ ", " + (int) filteredLotusValue);		
			mTextBioHarnessView.setText(bioHarnessBandName + ": " + mLotusRawValue + ", " + (int) filteredLotusValue + ", " + iLotusAlphaValue);		
			
			if (mIntroFade <= 0) {
				mBuddahImage.setAlpha(iBuddahAlphaValue);
				
				if (mBioHarnessParameterOfInterest == com.t2.compassionMeditation.Constants.PREF_BIOHARNESS_PNONE) {
					mLotusImage.setAlpha(0);
				}
				else {
					mLotusImage.setAlpha(iLotusAlphaValue);
				}
				
			}
			
			if (mSecondsRemaining-- > 0) {
				mCountdownTextView.setText(secsToHMS(mSecondsRemaining));	
			}
			else {
		    	handlePause("Session Complete"); // Allow opportinuty for a note
			}
		}
	};

	@Override
	protected void onPause() {
		Log.i(TAG, TAG +  " onPause");
		mDataUpdateTimer.purge();
    	mDataUpdateTimer.cancel();
    	currentMindsetData.saveScaleData();	
		mManager.removeListener(this);	

    	saveState();
    	mLoggingEnabled = false;
    	try {
        	if (mLogWriter != null)
        		mLogWriter.close();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		super.onPause();
	}

	@Override
	protected void onStop() {
		Log.i(TAG, TAG +  " onStop");
		if (!mSessionName.equalsIgnoreCase("")) {
			Toast.makeText(instance, "Saving: " + mSessionName, Toast.LENGTH_LONG).show();
		}
		
		super.onStop();
	}	
	
	@Override
	protected void onRestart() {
		Log.i(TAG, TAG +  " onRestart");
		
		super.onRestart();
	}

	@Override
	protected void onResume() {
		Log.i(TAG, TAG +  " onResume");
		restoreState(); // Opens log file
		// ... then we need to register a SPINEListener implementation to the SPINE manager instance
		// to receive sensor node data from the Spine server
		// (I register myself since I'm a SPINEListener implementation!)
		mManager.addListener(this);	        
		
		super.onResume();
	}


	void saveState()
	{
		 SharedPref.putBoolean(this, "LoggingEnabled", 	mLoggingEnabled);
		 SharedPref.putString(this, "SessionName", 	mSessionName);
	}
	
	void restoreState()
	{
		if (mSessionName.equalsIgnoreCase(""))
			mSessionName = SharedPref.getString(this, "SessionName", 	mSessionName);
		
		mMindsetBandOfInterest = SharedPref.getInt(this, 
				com.t2.compassionMeditation.Constants.PREF_BAND_OF_INTEREST ,
				com.t2.compassionMeditation.Constants.PREF_BAND_OF_INTEREST_DEFAULT);
		
		mBioHarnessParameterOfInterest = SharedPref.getInt(this, 
				com.t2.compassionMeditation.Constants.PREF_BIOHARNESS_PARAMETER_OF_INTEREST ,
				com.t2.compassionMeditation.Constants.PREF_BIOHARNESS_PARAMETER_OF_INTEREST_DEFAULT);
		
		
		if (!mSessionName.equalsIgnoreCase("")) {
			openLogFile();
		}
	}

	
	@Override
	public boolean onTouch(View arg0, MotionEvent arg1) {
		
		// Toggle showing screen buttons/controls
		if (mShowingControls) {
			mShowingControls = false;
			mCountdownTextView.setVisibility(View.INVISIBLE);
			mTextInfoView.setVisibility(View.INVISIBLE);
			mTextBioHarnessView.setVisibility(View.INVISIBLE);
			mPauseButton.setVisibility(View.INVISIBLE);
//			mBackButton.setVisibility(View.INVISIBLE);
			mSeekBar.setVisibility(View.INVISIBLE);
			
		}
		else {
			mShowingControls = true;
			mCountdownTextView.setVisibility(View.VISIBLE);
			mTextInfoView.setVisibility(View.VISIBLE);
			mTextBioHarnessView.setVisibility(View.VISIBLE);
			mPauseButton.setVisibility(View.VISIBLE);
//			mBackButton.setVisibility(View.VISIBLE);
//			mSeekBar.setVisibility(View.VISIBLE);
			mSeekBar.setVisibility(mShowAGain ? View.VISIBLE :View.INVISIBLE);

		}
		return false;
	}

	@Override
	public void onProgressChanged(SeekBar arg0, int arg1, boolean arg2) {
		// *** For test/debug only
//		double  alpha = arg1 * 2.5;
//		mBuddahImage.setAlpha((int) alpha);
//		mTextInfoView.setText(Integer.toString((int)alpha));
//		double  alpha = arg1 * 2.5;
		mAlphaGain = arg1/10;
	}

	@Override
	public void onStartTrackingTouch(SeekBar arg0) {
	}


	@Override
	public void onStopTrackingTouch(SeekBar arg0) {
		
//		mAlphaGain = SharedPref.getFloat(this, 
//		com.t2.compassionMeditation.Constants.PREF_ALPHA_GAIN, 	
//		com.t2.compassionMeditation.Constants.PREF_ALPHA_GAIN_DEFAULT);
		SharedPref.putFloat(this,
			com.t2.compassionMeditation.Constants.PREF_ALPHA_GAIN, 	
			(float) mAlphaGain);
		
		
	}
	


	/**
	 * Handles the pause button press
	 *   Brings up a dialog that allows the user to either restart, or quit
	 *   Note that in any case the text entered by the user is saved to the log file
	 */
	public void handlePause(String message) {
		AlertDialog.Builder alert1 = new AlertDialog.Builder(this);

		alert1.setTitle(message);
		alert1.setMessage("Notes:");
		mPaused = true;

		// Set an EditText view to get user input 
		final EditText input = new EditText(this);
		alert1.setView(input);

		
		// User pressed the quit key, exit the activity
		alert1.setPositiveButton("Quit", new DialogInterface.OnClickListener() {
		public void onClick(DialogInterface dialog, int whichButton) {
			
			addNoteToLog(input.getText().toString());
			Toast.makeText(instance, "Saving: " + mSessionName, Toast.LENGTH_LONG).show();
			mCurrentBioSession.comments += input.getText();
			
			// Udpate the database with the current session
			try {
				
				mBioSessionDao.create(mCurrentBioSession);
				
			} catch (SQLException e1) {
				Log.e(TAG, "Error saving current session to database", e1);
			}			
			
			
			// Save catlog file for possible debugging
			try {
			    File filename = new File(Environment.getExternalStorageDirectory() + "/" + mLogCatName); 
			    filename.createNewFile(); 
			    String cmd = "logcat -d -f "+filename.getAbsolutePath();
			    Runtime.getRuntime().exec(cmd);
			} catch (IOException e) {
			    // TODO Auto-generated catch block
			    e.printStackTrace();
			}			
			
			finish();
		  
		  }
		});

		alert1.setOnCancelListener(new DialogInterface.OnCancelListener() {
			@Override
			public void onCancel(DialogInterface arg0) {
				mPaused = false;
	      		mIntroFade = 255;
				
			}
		}
		);

		alert1.setNegativeButton("Start", new DialogInterface.OnClickListener() {
		  public void onClick(DialogInterface dialog, int whichButton) {
				mPaused = false;
				addNoteToLog(input.getText().toString());
				mCurrentBioSession.comments += input.getText();

	      		mIntroFade = 255;

		  }
		});

		alert1.show();
	}

	/**
	 * Writes a specific note to the log - adding a time stamp
	 * @param note Note to save to log
	 */
	void addNoteToLog(String note) {
		SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
		
		String currentDateTimeString = DateFormat.getDateInstance().format(new Date());				
		currentDateTimeString = sdf.format(new Date());
		
		String logData = currentDateTimeString + ", " + note + "\n";
		
        try {
        	if (mLogWriter != null)
        		mLogWriter.write(logData);
		} catch (IOException e) {
			Log.e(TAG, e.toString());
		}		
	}
	
	void closeLogFile() {
	}
	
	/**
	 * Sets the session name (file name to be saved) based on current time/date
	 */
	private void setNewSessionName(String userName) {
		
	}
	
	void openLogFile() {
		mLoggingEnabled = true;	
		
		if (mLoggingEnabled) {
//			if (mLoggingEnabled && this.mSelectedUserName != null) {

			Toast.makeText(instance, "Starting: " + mSessionName, Toast.LENGTH_LONG).show();

			// Open a file for saving data
    		try {
    		    File root = Environment.getExternalStorageDirectory();
    		    if (root.canWrite()){
    		        File gpxfile = new File(root, mSessionName);
    		        FileWriter gpxwriter = new FileWriter(gpxfile, true); // open for append
    		        mLogWriter = new BufferedWriter(gpxwriter);
    		    } 
    		    else {
        		    Log.e(TAG, "Could not write file " );
        			AlertDialog.Builder alert = new AlertDialog.Builder(this);
        			
        			alert.setTitle("ERROR");
        			alert.setMessage("Cannot write to file");	
        	    	alert.setPositiveButton(R.string.alert_dialog_ok, new DialogInterface.OnClickListener() {
        	            public void onClick(DialogInterface dialog, int whichButton) {
        	            	mIntroFade = 255;
        	            }
        	        });
        			
        			alert.show();			
    		    	
    		    }
    		} catch (IOException e) {
    		    Log.e(TAG, "Could not write file " + e.getMessage());
    			AlertDialog.Builder alert = new AlertDialog.Builder(this);
    			
    			alert.setTitle("ERROR");
    			alert.setMessage("Cannot write to file");	
    			alert.show();			
    		    
    		}
		}
	}


	
}