/*
 *  Copyright 2015 The WebRTC Project Authors. All rights reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the source
 *  tree. An additional intellectual property rights grant can be found
 *  in the file PATENTS.  All contributing project authors may
 *  be found in the AUTHORS file in the root of the source tree.
 */

package d.d.meshenger.call;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.FragmentTransaction;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.support.annotation.Nullable;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import android.widget.Toast;
import java.io.IOException;
import java.lang.RuntimeException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import d.d.meshenger.call.AppRTCAudioManager.AudioDevice;
import d.d.meshenger.call.AppRTCAudioManager.AudioManagerEvents;
//import d.d.meshenger.AppRTCClient.RoomConnectionParameters;
import d.d.meshenger.call.DirectRTCClient;
import d.d.meshenger.call.AppRTCClient.SignalingParameters;
import d.d.meshenger.call.PeerConnectionClient.DataChannelParameters;
import d.d.meshenger.call.PeerConnectionClient.PeerConnectionParameters;
import org.webrtc.Camera1Enumerator;
import org.webrtc.Camera2Enumerator;
import org.webrtc.CameraEnumerator;
import org.webrtc.EglBase;
import org.webrtc.FileVideoCapturer;
import org.webrtc.IceCandidate;
import org.webrtc.Logging;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RendererCommon.ScalingType;
import org.webrtc.ScreenCapturerAndroid;
import org.webrtc.SessionDescription;
import org.webrtc.StatsReport;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoFileRenderer;
import org.webrtc.VideoFrame;
import org.webrtc.VideoSink;

import d.d.meshenger.MainService;
import d.d.meshenger.Settings;
import d.d.meshenger.R;

import static d.d.meshenger.call.DirectRTCClient.CallDirection.OUTGOING;

/**
 * Activity for peer connection call setup, call waiting
 * and call view.
 */
public class CallActivity extends Activity implements DirectRTCClient.SignalingEvents,
                                                      PeerConnectionClient.PeerConnectionEvents,
                                                      CallFragment.OnCallEvents {
  private static final String TAG = "CallActivity";
  private static final int CAPTURE_PERMISSION_REQUEST_CODE = 1;

  // List of mandatory application permissions.
  private static final String[] MANDATORY_PERMISSIONS = {"android.permission.MODIFY_AUDIO_SETTINGS",
      "android.permission.RECORD_AUDIO", "android.permission.INTERNET"}; //, "android.permission.CAMERA"};

  // Peer connection statistics callback period in ms.
  private static final int STAT_CALLBACK_PERIOD = 1000;

  private static class ProxyVideoSink implements VideoSink {
    private VideoSink target;

    @Override
    synchronized public void onFrame(VideoFrame frame) {
      if (target == null) {
        Logging.d(TAG, "Dropping frame in proxy because target is null.");
        return;
      }

      target.onFrame(frame);
    }

    synchronized public void setTarget(VideoSink target) {
      this.target = target;
    }
  }

  private final ProxyVideoSink remoteProxyRenderer = new ProxyVideoSink();
  private final ProxyVideoSink localProxyVideoSink = new ProxyVideoSink();
  @Nullable private PeerConnectionClient peerConnectionClient;
  @Nullable
  private DirectRTCClient appRtcClient;
  @Nullable
  private SignalingParameters signalingParameters;
  @Nullable private AppRTCAudioManager audioManager;
  @Nullable
  private SurfaceViewRenderer pipRenderer;
  @Nullable
  private SurfaceViewRenderer fullscreenRenderer;
  @Nullable
  private VideoFileRenderer videoFileRenderer;
  private final List<VideoSink> remoteSinks = new ArrayList<>();
  private Toast logToast;
  private boolean activityRunning; // needed?
  @Nullable
  private PeerConnectionParameters peerConnectionParameters;
  private boolean connected;
  private boolean isError;
  private boolean callControlFragmentVisible = true;
  private long callStartedTimeMs;
  private boolean micEnabled = true;
  private static Intent mediaProjectionPermissionResultData; // needed?
  private static int mediaProjectionPermissionResultCode; // needed?
  // True if local view is in the fullscreen renderer.
  private boolean isSwappedFeeds;

  // Controls
  private CallFragment callFragment;
  private HudFragment hudFragment;
  //private CpuMonitor cpuMonitor; //statsFragment
  private EglBase eglBase; //temporary


  @Override
  // TODO(bugs.webrtc.org/8580): LayoutParams.FLAG_TURN_SCREEN_ON and
  // LayoutParams.FLAG_SHOW_WHEN_LOCKED are deprecated.
  @SuppressWarnings("deprecation")
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    Thread.setDefaultUncaughtExceptionHandler(new UnhandledExceptionHandler(this));

    // Set window styles for fullscreen-window size. Needs to be done before
    // adding content.
    requestWindowFeature(Window.FEATURE_NO_TITLE);
    getWindow().addFlags(LayoutParams.FLAG_FULLSCREEN | LayoutParams.FLAG_KEEP_SCREEN_ON
        | LayoutParams.FLAG_SHOW_WHEN_LOCKED | LayoutParams.FLAG_TURN_SCREEN_ON);
    getWindow().getDecorView().setSystemUiVisibility(getSystemUiVisibility());
    setContentView(R.layout.activity_call);

    connected = false;
    signalingParameters = null;

    // Create UI controls.
    pipRenderer = findViewById(R.id.pip_video_view);
    fullscreenRenderer = findViewById(R.id.fullscreen_video_view);
    //fullscreenRenderer.setBackgroundColor(Color.parseColor("#00aacc"));

/*
    if (true) {
      pipRenderer.setVisibility(View.GONE);
      fullscreenRenderer.setBackgroundColor(Color.parseColor("#00aacc"));
      waitFragment = new WaitFragment();
      {
        FragmentTransaction ft = getFragmentManager().beginTransaction();
        ft.add(R.id.wait_fragment_container, waitFragment);
        ft.commit();
      }

      //fullscreenRenderer.setOnClickListener((View view) -> {
        Log.d(TAG, "show waitFragment");
        FragmentTransaction ft = getFragmentManager().beginTransaction();
        ft.show(waitFragment);
        ft.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE);
        ft.commit();
      //});
      //pipRenderer.setVisibility(View.GONE);
      //fullscreenRenderer.setVisibility(View.GONE);
      return;
    }
*/
    callFragment = new CallFragment();
    hudFragment = new HudFragment();
    //waitFragment = new WaitFragment();

    // Show/hide call control fragment on view click.
    fullscreenRenderer.setOnClickListener((View view) -> {
      toggleCallControlFragmentVisibility();
    });

    // Swap feeds on pip view click.
    pipRenderer.setOnClickListener((View view) -> {
      setSwappedFeeds(!isSwappedFeeds);
    });

    remoteSinks.add(remoteProxyRenderer);

    final Intent intent = getIntent();
    //final EglBase eglBase = EglBase.create();
    eglBase = EglBase.create();

    // Create video renderers.
    pipRenderer.init(eglBase.getEglBaseContext(), null);
    pipRenderer.setScalingType(ScalingType.SCALE_ASPECT_FIT);

    fullscreenRenderer.init(eglBase.getEglBaseContext(), null);
    fullscreenRenderer.setScalingType(ScalingType.SCALE_ASPECT_FILL);

    pipRenderer.setZOrderMediaOverlay(true);
    pipRenderer.setEnableHardwareScaler(true /* enabled */);
    fullscreenRenderer.setEnableHardwareScaler(false /* enabled */);
    // Start with local feed in fullscreen and swap it to the pip when the call is connected.
    setSwappedFeeds(true /* isSwappedFeeds */);

    // Check for mandatory permissions.
    for (String permission : MANDATORY_PERMISSIONS) {
      if (checkCallingOrSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
        logAndToast("Permission " + permission + " is not granted");
        setResult(RESULT_CANCELED);
        finish();
        return;
      }
    }

    DataChannelParameters dataChannelParameters = null;
    if (intent.getBooleanExtra("EXTRA_DATA_CHANNEL_ENABLED", true)) {
      dataChannelParameters = new DataChannelParameters(
        true, // ORDERED
        -1, // MAX_RETRANSMITS_MS
        -1, // MAX_RETRANSMITS
        "", //PROTOCOL
        false, //NEGOTIATED
        -1 // ID
      );
      dataChannelParameters.debug();
    }
    Settings settings = MainService.instance.getSettings();
    peerConnectionParameters = new PeerConnectionParameters(
      settings.getReceiveVideo(),
      settings.getSendVideo(),
      settings.getReceiveAudio(),
      settings.getSendAudio(),
      true, // VIDEO_CALL // TODO: remove
      0, // VIDEO_WIDTH
      0, // VIDEO_HEIGHT
      0, // VIDEO_FPS
      0, // VIDEO_BITRATE
      settings.getVideoCodec(),
      true, // HWCODEC_ENABLED
      false, // FLEXFEC_ENABLED
      0, // AUDIO_BITRATE
      settings.getAudioCodec(),
      settings.getAudioProcessing(), // NOAUDIOPROCESSING_ENABLED
      false, // OPENSLES_ENABLED
      false, // DISABLE_BUILT_IN_AEC
      false, // DISABLE_BUILT_IN_AGC
      false, // DISABLE_BUILT_IN_NS
      false, // DISABLE_WEBRTC_AGC_AND_HPF
      dataChannelParameters
    );
    peerConnectionParameters.debug();

/*
    // Create CPU monitor
    if (CpuMonitor.isSupported()) {
      cpuMonitor = new CpuMonitor(this);
      hudFragment.setCpuMonitor(cpuMonitor);
    }
*/
    // Activate call and HUD fragments and start the call.
    FragmentTransaction ft = getFragmentManager().beginTransaction();
    ft.add(R.id.call_fragment_container, callFragment);
    ft.add(R.id.hud_fragment_container, hudFragment);
    ft.commit();

// my addition

    appRtcClient = MainService.currentCall;

    if (appRtcClient == null) {
      disconnectWithErrorMessage("No connection expected!");
      return;
    }

    appRtcClient.setEventListener(this);

    switch (appRtcClient.getCallDirection()) {
    case INCOMING:
      Log.d(TAG, "Incoming call");
      // TODO: use blockUnknown and Contact.isBlocked()

      // Incoming Call, socket is set
      if (MainService.instance.getSettings().getAutoAcceptCall()) {
        startCall(); // calls appRtcClient.connectToRoom(); => starts connect thread()
      } else {
        // start ringing and wait for connect call
        Log.d(TAG, "start ringing");
        startCall(); //TODO: remove...
      }
      break;
    case OUTGOING:
      Log.d(TAG, "Outgoing call");

      // Outgoing Call, contact is set
      if (MainService.instance.getSettings().getAutoConnectCall()) {
        startCall(); // calls appRtcClient.connectToRoom(); => starts connect thread()
      } else {
        Log.d(TAG, "wait for explicit button call to start call");
        // wait for explicit connect button press to start call
      }
      break;
    default:
      reportError("Invalid call direction!");
      return;
    }
  }

  @TargetApi(17)
  private DisplayMetrics getDisplayMetrics() {
    DisplayMetrics displayMetrics = new DisplayMetrics();
    WindowManager windowManager =
        (WindowManager) getApplication().getSystemService(Context.WINDOW_SERVICE);
    windowManager.getDefaultDisplay().getRealMetrics(displayMetrics);
    return displayMetrics;
  }

  @TargetApi(19)
  private static int getSystemUiVisibility() {
    int flags = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
      flags |= View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
    }
    return flags;
  }

  // we first started to ask for permission
  // if successfull => start call
  @Override
  public void onActivityResult(int requestCode, int resultCode, Intent data) {
    if (requestCode == CAPTURE_PERMISSION_REQUEST_CODE) {
      mediaProjectionPermissionResultCode = resultCode;
      mediaProjectionPermissionResultData = data;
      startCall();
    }
  }

  private boolean useCamera2() {
    return Camera2Enumerator.isSupported(this) && getIntent().getBooleanExtra("EXTRA_CAMERA2", true);
  }

  private boolean captureToTexture() {
    return true; //getIntent().getBooleanExtra(EXTRA_CAPTURETOTEXTURE_ENABLED, false);
  }

  private @Nullable VideoCapturer createCameraCapturer(CameraEnumerator enumerator) {
    final String[] deviceNames = enumerator.getDeviceNames();

    // First, try to find front facing camera
    Logging.d(TAG, "Looking for front facing cameras.");
    for (String deviceName : deviceNames) {
      if (enumerator.isFrontFacing(deviceName)) {
        Logging.d(TAG, "Creating front facing camera capturer.");
        VideoCapturer videoCapturer = enumerator.createCapturer(deviceName, null);

        if (videoCapturer != null) {
          return videoCapturer;
        }
      }
    }

    // Front facing camera not found, try something else
    Logging.d(TAG, "Looking for other cameras.");
    for (String deviceName : deviceNames) {
      if (!enumerator.isFrontFacing(deviceName)) {
        Logging.d(TAG, "Creating other camera capturer.");
        VideoCapturer videoCapturer = enumerator.createCapturer(deviceName, null);

        if (videoCapturer != null) {
          return videoCapturer;
        }
      }
    }

    return null;
  }

  // Activity interfaces
  @Override
  public void onStop() {
    super.onStop();
    activityRunning = false;
    // Don't stop the video when using screencapture to allow user to show other apps to the remote
    // end.
    if (peerConnectionClient != null /* && !screencaptureEnabled*/) {
      peerConnectionClient.stopVideoSource();
    }
    /*
    if (cpuMonitor != null) {
      cpuMonitor.pause();
    }*/
  }

  @Override
  public void onStart() {
    super.onStart();
    activityRunning = true;
    // Video is not paused for screencapture. See onPause.
    if (peerConnectionClient != null /* && !screencaptureEnabled*/) {
      peerConnectionClient.startVideoSource();
    }
    /*
    if (cpuMonitor != null) {
      cpuMonitor.resume();
    }*/
  }

  @Override
  protected void onDestroy() {
    Thread.setDefaultUncaughtExceptionHandler(null);
    disconnect();
    if (logToast != null) {
      logToast.cancel();
    }
    activityRunning = false;
    super.onDestroy();
  }

  // CallFragment.OnCallEvents interface implementation.
  @Override
  public void onCallHangUp() {
    disconnect();
  }

  @Override
  public void onCameraSwitch() {
    if (peerConnectionClient != null) {
      peerConnectionClient.switchCamera();
    }
  }

  // added by myself
  @Override
  public void onVideoMirrorSwitch(boolean mirror) {
    fullscreenRenderer.setMirror(mirror); //!fullscreenRenderer.getMirror());
  }

  @Override
  public void onVideoScalingSwitch(ScalingType scalingType) {
    fullscreenRenderer.setScalingType(scalingType);
  }

  @Override
  public void onCaptureFormatChange(int width, int height, int framerate) {
    if (peerConnectionClient != null) {
      peerConnectionClient.changeCaptureFormat(width, height, framerate);
    }
  }

  @Override
  public boolean onToggleMic() {
    if (peerConnectionClient != null) {
      micEnabled = !micEnabled;
      peerConnectionClient.setAudioEnabled(micEnabled);
    }
    return micEnabled;
  }

  // Helper functions.
  private void toggleCallControlFragmentVisibility() {
    Log.d(TAG, "toggleCallControlFragmentVisibility");
    if (!connected || !callFragment.isAdded()) {
      return;
    }

    // Show/hide call control fragment
    callControlFragmentVisible = !callControlFragmentVisible;
    FragmentTransaction ft = getFragmentManager().beginTransaction();
    if (callControlFragmentVisible) {
      ft.show(callFragment);
      ft.show(hudFragment);
    } else {
      ft.hide(callFragment);
      ft.hide(hudFragment);
    }
    ft.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE);
    ft.commit();

/*
    // TODO: make more elegant
    if (appRtcClient.getCallDirection() == OUTGOING) {
      callFragment.onOutgoingCall();
    } else {
      callFragment.onIncomingCall();
    }
*/
  }

  private void startCall() {
    //if (peerConnectionClient != null) {
    //  logAndToast("Call already started");
    //  return;
    //}

    if (appRtcClient == null) {
      Log.e(TAG, "AppRTC client is not allocated for a call.");
      return;
    }

    // Create peer connection client.
    peerConnectionClient = new PeerConnectionClient(
        getApplicationContext(), eglBase, peerConnectionParameters, CallActivity.this);

    PeerConnectionFactory.Options options = new PeerConnectionFactory.Options();
    //options.disableNetworkMonitor = true; // does not work! from email by dante carvalho to fix connection in case of tethering
    peerConnectionClient.createPeerConnectionFactory(options);


    callStartedTimeMs = System.currentTimeMillis();

    // Start room connection.
    //logAndToast(getString(R.string.connecting_to, roomConnectionParameters.roomUrl));
    logAndToast("appRtcClient.connectToRoom");
    appRtcClient.connectToRoom(); //this.contact_address, this.contact_port);

    // Create and audio manager that will take care of audio routing,
    // audio modes, audio device enumeration etc.
    audioManager = AppRTCAudioManager.create(getApplicationContext(), MainService.instance.getSettings().getSpeakerphone());
    // Store existing audio settings and change audio mode to
    // MODE_IN_COMMUNICATION for best possible VoIP performance.
    Log.d(TAG, "Starting the audio manager...");
    audioManager.start(new AudioManagerEvents() {
      // This method will be called each time the number of available audio
      // devices has changed.
      @Override
      public void onAudioDeviceChanged(
          AudioDevice audioDevice, Set<AudioDevice> availableAudioDevices) {
        onAudioManagerDevicesChanged(audioDevice, availableAudioDevices);
      }
    });
  }

  // Should be called from UI thread
  private void callConnected() {
    final long delta = System.currentTimeMillis() - callStartedTimeMs;
    Log.i(TAG, "Call connected: delay=" + delta + "ms");
    if (peerConnectionClient == null || isError) {
      Log.w(TAG, "Call is connected in closed or error state");
      return;
    }
    // Enable statistics callback.
    peerConnectionClient.enableStatsEvents(true, STAT_CALLBACK_PERIOD);
    setSwappedFeeds(false /* isSwappedFeeds */);
  }

  // This method is called when the audio manager reports audio device change,
  // e.g. from wired headset to speakerphone.
  private void onAudioManagerDevicesChanged(
      final AudioDevice device, final Set<AudioDevice> availableDevices) {
    Log.d(TAG, "onAudioManagerDevicesChanged: " + availableDevices + ", "
            + "selected: " + device);
    // TODO(henrika): add callback handler.
  }

  // Disconnect from remote resources, dispose of local resources, and exit.
  private void disconnect() {
    activityRunning = false;
    remoteProxyRenderer.setTarget(null);
    localProxyVideoSink.setTarget(null);
    if (appRtcClient != null) {
      logAndToast("appRtcClient.disconnectFromRoom");
      appRtcClient.disconnectFromRoom();
      appRtcClient = null;
      MainService.currentCall = null; // free instance (TODO: use set that uses synchronize)
    }
    if (pipRenderer != null) {
      pipRenderer.release();
      pipRenderer = null;
    }
    if (videoFileRenderer != null) {
      videoFileRenderer.release();
      videoFileRenderer = null;
    }
    if (fullscreenRenderer != null) {
      fullscreenRenderer.release();
      fullscreenRenderer = null;
    }
    if (peerConnectionClient != null) {
      peerConnectionClient.close();
      peerConnectionClient = null;
    }
    if (audioManager != null) {
      audioManager.stop();
      audioManager = null;
    }
    if (connected && !isError) {
      setResult(RESULT_OK);
    } else {
      setResult(RESULT_CANCELED);
    }
    finish();
  }

  private void disconnectWithErrorMessage(final String errorMessage) {
    if (/*commandLineRun ||*/ !activityRunning) {
      Log.e(TAG, "Critical error: " + errorMessage);
      disconnect();
    } else {
      new AlertDialog.Builder(this)
              .setTitle("Connection error")
              .setMessage(errorMessage)
              .setCancelable(false)
              .setNeutralButton(R.string.ok,
                      new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int id) {
                          dialog.cancel();
                          disconnect();
                        }
                      })
              .create()
              .show();
    }
  }

  // Log |msg| and Toast about it.
  private void logAndToast(String msg) {
    Log.d(TAG, msg);
    if (logToast != null) {
      logToast.cancel();
    }
    logToast = Toast.makeText(this, msg, Toast.LENGTH_SHORT);
    logToast.show();
  }

  private void reportError(final String description) {
    runOnUiThread(() -> {
      if (!isError) {
        isError = true;
        disconnectWithErrorMessage(description);
      }
    });
  }

  private @Nullable VideoCapturer createVideoCapturer() {
    final VideoCapturer videoCapturer;
    /*
    String videoFileAsCamera = getIntent().getStringExtra(EXTRA_VIDEO_FILE_AS_CAMERA);
    if (videoFileAsCamera != null) {
      try {
        videoCapturer = new FileVideoCapturer(videoFileAsCamera);
      } catch (IOException e) {
        reportError("Failed to open video file for emulated camera");
        return null;
      }
    } else if (screencaptureEnabled) {
      return createScreenCapturer();
    } else*/ if (useCamera2()) {
      if (!captureToTexture()) {
        reportError("Camera2 only supports capturing to texture. Either disable Camera2 or enable capturing to texture in the options.");
        return null;
      }

      Logging.d(TAG, "Creating capturer using camera2 API.");
      videoCapturer = createCameraCapturer(new Camera2Enumerator(this));
    } else {
      Logging.d(TAG, "Creating capturer using camera1 API.");
      videoCapturer = createCameraCapturer(new Camera1Enumerator(captureToTexture()));
    }
    if (videoCapturer == null) {
      reportError("Failed to open camera");
      return null;
    }
    return videoCapturer;
  }

  private void setSwappedFeeds(boolean isSwappedFeeds) {
    Logging.d(TAG, "setSwappedFeeds: " + isSwappedFeeds);
    this.isSwappedFeeds = isSwappedFeeds;
    localProxyVideoSink.setTarget(isSwappedFeeds ? fullscreenRenderer : pipRenderer);
    remoteProxyRenderer.setTarget(isSwappedFeeds ? pipRenderer : fullscreenRenderer);
    fullscreenRenderer.setMirror(isSwappedFeeds);
    pipRenderer.setMirror(!isSwappedFeeds);
  }

  // -----Implementation of AppRTCClient.AppRTCSignalingEvents ---------------
  // All callbacks are invoked from websocket signaling looper thread and
  // are routed to UI thread.
  private void onConnectedToRoomInternal(final SignalingParameters params) {
    final long delta = System.currentTimeMillis() - callStartedTimeMs;

    signalingParameters = params;
    logAndToast("Creating peer connection, delay=" + delta + "ms");
    VideoCapturer videoCapturer = null;
    if (peerConnectionParameters.videoCallEnabled) {
      videoCapturer = createVideoCapturer();
    }
    peerConnectionClient.createPeerConnection2(
        localProxyVideoSink, remoteSinks, videoCapturer, signalingParameters.iceServers);

    if (signalingParameters.initiator) {
      logAndToast("Creating OFFER...");
      // Create offer. Offer SDP will be sent to answering client in
      // PeerConnectionEvents.onLocalDescription event.
      peerConnectionClient.createOffer();
    } else {
      if (params.offerSdp != null) {
        peerConnectionClient.setRemoteDescription(params.offerSdp);
        logAndToast("Creating ANSWER...");
        // Create answer. Answer SDP will be sent to offering client in
        // PeerConnectionEvents.onLocalDescription event.
        peerConnectionClient.createAnswer();
      }
      if (params.iceCandidates != null) {
        // Add remote ICE candidates from room.
        for (IceCandidate iceCandidate : params.iceCandidates) {
          peerConnectionClient.addRemoteIceCandidate(iceCandidate);
        }
      }
    }
  }

  // called from DirectRTCClient.onTCPConnected (if we are server)
  // and DirectRTCClient.onTCPMessage (with sdp from offer)
  @Override
  public void onConnectedToRoom(final SignalingParameters params) {
    // TODO: ringing (if not auto accept)
    runOnUiThread(() -> {
      onConnectedToRoomInternal(params);
    });
  }

  // called from DirectRTCClient.onTCPMessage
  @Override
  public void onRemoteDescription(final SessionDescription desc) {
    final long delta = System.currentTimeMillis() - callStartedTimeMs;
    runOnUiThread(() -> {
      if (peerConnectionClient == null) {
        Log.e(TAG, "Received remote SDP for non-initilized peer connection.");
        return;
      }
      logAndToast("Received remote " + desc.type + ", delay=" + delta + "ms");
      peerConnectionClient.setRemoteDescription(desc);
      if (!signalingParameters.initiator) {
        logAndToast("Creating ANSWER...");
        // Create answer. Answer SDP will be sent to offering client in
        // PeerConnectionEvents.onLocalDescription event.
        peerConnectionClient.createAnswer();
      }
    });
  }

  @Override
  public void onRemoteIceCandidate(final IceCandidate candidate) {
    runOnUiThread(() -> {
      if (peerConnectionClient == null) {
        Log.e(TAG, "Received ICE candidate for a non-initialized peer connection.");
        return;
      }
      peerConnectionClient.addRemoteIceCandidate(candidate);
    });
  }

  @Override
  public void onRemoteIceCandidatesRemoved(final IceCandidate[] candidates) {
    runOnUiThread(() -> {
      if (peerConnectionClient == null) {
        Log.e(TAG, "Received ICE candidate removals for a non-initialized peer connection.");
        return;
      }
      peerConnectionClient.removeRemoteIceCandidates(candidates);
    });
  }

  @Override
  public void onChannelClose() {
    runOnUiThread(() -> {
      logAndToast("Remote end hung up; dropping PeerConnection");
      disconnect();
    });
  }

  @Override
  public void onChannelError(final String description) {
    reportError(description);
  }

  // -----Implementation of PeerConnectionClient.PeerConnectionEvents.---------
  // Send local peer connection SDP and ICE candidates to remote party.
  // All callbacks are invoked from peer connection client looper thread and
  // are routed to UI thread.
  @Override
  public void onLocalDescription(final SessionDescription desc) {
    final long delta = System.currentTimeMillis() - callStartedTimeMs;
    runOnUiThread(() -> {
      if (appRtcClient != null) {
        logAndToast("Sending " + desc.type + ", delay=" + delta + "ms");
        if (signalingParameters.initiator) {
          logAndToast("appRtcClient.sendOfferSdp");
          appRtcClient.sendOfferSdp(desc);
        } else {
          logAndToast("appRtcClient.sendAnswerSdp");
          appRtcClient.sendAnswerSdp(desc);
        }
      }
      if (peerConnectionParameters.videoMaxBitrate > 0) {
        Log.d(TAG, "Set video maximum bitrate: " + peerConnectionParameters.videoMaxBitrate);
        peerConnectionClient.setVideoMaxBitrate(peerConnectionParameters.videoMaxBitrate);
      }
    });
  }

  @Override
  public void onIceCandidate(final IceCandidate candidate) {
    Log.d(TAG, "appRtcClient.sendLocalIceCandidate");
    runOnUiThread(() -> {
      if (appRtcClient != null) {
        appRtcClient.sendLocalIceCandidate(candidate);
      }
    });
  }

  @Override
  public void onIceCandidatesRemoved(final IceCandidate[] candidates) {
    Log.d(TAG, "appRtcClient.sendLocalIceCandidateRemovals");
    runOnUiThread(() -> {
      if (appRtcClient != null) {
        appRtcClient.sendLocalIceCandidateRemovals(candidates);
      }
    });
  }

  @Override
  public void onIceConnected() {
    final long delta = System.currentTimeMillis() - callStartedTimeMs;
    runOnUiThread(() -> {
      logAndToast("ICE connected, delay=" + delta + "ms");
    });
  }

  @Override
  public void onIceDisconnected() {
    runOnUiThread(() -> {
        logAndToast("ICE disconnected");
    });
  }

// not called?
  @Override
  public void onConnected() {
    final long delta = System.currentTimeMillis() - callStartedTimeMs;
    runOnUiThread(() -> {
      logAndToast("DTLS connected, delay=" + delta + "ms");
      connected = true;
      callConnected();
    });
  }

  @Override
  public void onDisconnected() {
    runOnUiThread(() -> {
      logAndToast("DTLS disconnected");
      connected = false;
      disconnect();
    });
  }

  @Override
  public void onPeerConnectionClosed() {}

  @Override
  public void onPeerConnectionStatsReady(final StatsReport[] reports) {
    runOnUiThread(() -> {
      if (!isError && connected) {
        hudFragment.updateEncoderStatistics(reports);
      }
    });
  }

  @Override
  public void onPeerConnectionError(final String description) {
    reportError(description);
  }
}