package rimp.rild.com.android.bluechatlocationexchange;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationProvider;
import android.net.Uri;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;

public class HostActivity extends Activity implements LocationListener {
    private static final String TAG = MainActivity.class.getSimpleName();

    public static final int REQUEST_DISCOVERABLE = 1;
    public static final int PICK_IMAGE = 2;

    //---------------------GPS
    // 更新時間(目安)
    private static final int LOCATION_UPDATE_MIN_TIME = 0;
    // 更新距離(目安)
    private static final int LOCATION_UPDATE_MIN_DISTANCE = 0;
    //---------------------

    //View
    private EditText mMessage;

    Button mAttachButton;
    Button mLocateButton; //(GPS)位置情報取得ボタン
    Button mSendButton;

    private String mUsername;
    private String mChatRoomName;
    private BluetoothAdapter mBluetoothAdapter;
    private ArrayList<BluetoothSocket> mSockets;
    private AcceptThread mAcceptThread;

    private ChatManager mChatManager;

    //---------------------
    // GPS用
    private LocationManager mLocationManager;
    //---------------------
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chatroom);
        if (getActionBar() != null) {
            getActionBar().setDisplayHomeAsUpEnabled(true);
        }

        mAttachButton = (Button) findViewById(R.id.attach);
        mLocateButton = (Button) findViewById(R.id.get_location); //(GPS)位置情報取得ボタン
        mSendButton = (Button) findViewById(R.id.send);

        mMessage = (EditText) findViewById(R.id.message);
        mChatManager = new ChatManager(this, true);

        mAttachButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                uploadAttachment();
            }
        });

        //---------------------GPS用
        mLocateButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                requestLocationUpdates();
            }
        });
        //---------------------ここまで


        mSendButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                sendMessage();
            }
        });

        mLocationManager = (LocationManager)this.getSystemService(Service.LOCATION_SERVICE);

        initializeRoom();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.host, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            Intent i = new Intent(this, MainActivity.class);
            startActivity(i);
            finish();
        } else if (id == R.id.action_reopen) {
            if (mAcceptThread != null) {
                mAcceptThread.cancel();
            }
            initializeBluetooth();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    public void initializeRoom() {
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        final InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);

        // Retrieve username
        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(this);
        mUsername = sharedPref.getString("username", mBluetoothAdapter.getName());

        // Set up ChatRoom naming input
        final EditText nameInput = new EditText(this);
        nameInput.setSingleLine();
        nameInput.setImeOptions(EditorInfo.IME_ACTION_DONE);

        // Set up ChatRoom naming dialog
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setMessage("Enter your ChatRoom name");
        builder.setView(nameInput);
        builder.setPositiveButton("Submit", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialogInterface, int which) {
                mChatRoomName = nameInput.getText().toString();

                if (getActionBar() != null) {
                    getActionBar().setTitle(mChatRoomName);
                }

                imm.hideSoftInputFromWindow(nameInput.getWindowToken(), 0);
                initializeBluetooth();
            }
        });
        builder.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialogInterface, int i) {
                imm.hideSoftInputFromWindow(nameInput.getWindowToken(), 0);
                finish();
            }
        });
        builder.setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override
            public void onCancel(DialogInterface dialogInterface) {
                finish();
            }
        });

        // Show the dialog and disable the submit button until the name is longer than 0 characters
        final AlertDialog dialog = builder.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0);

        nameInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence charSequence, int i, int i2, int i3) {}

            @Override
            public void onTextChanged(CharSequence charSequence, int i, int i2, int i3) {
                if (charSequence.length() > 0) {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                } else {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                }
            }

            @Override
            public void afterTextChanged(Editable editable) {}
        });
    }

    private void initializeBluetooth() {
        mSockets = new ArrayList<BluetoothSocket>();

        Intent i = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
        i.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
        startActivityForResult(i, REQUEST_DISCOVERABLE);
    }

    //---------------------GPS用
    private void setLocationToEditText() {
        Location location = mLocationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
        mMessage.setText(getLocationMessage(location));
    }
    //---------------------ここまで

    private void uploadAttachment() {
        Intent i = new Intent();
        i.setType("image/*");
        i.setAction(Intent.ACTION_PICK);
        startActivityForResult(Intent.createChooser(i, "Select Picture"), PICK_IMAGE);
    }

    private void sendMessage() {
        byte[] byteArray;

        if (mMessage.getText().toString().length() == 0) {
            return;
        }

        try {
            byte[] messageBytes = mMessage.getText().toString().getBytes();
            byteArray = mChatManager.buildPacket(
                    ChatManager.MESSAGE_SEND,
                    mUsername,
                    messageBytes
            );
        } catch (Exception e) {
            return;
        }

        mChatManager.writeMessage(byteArray);
        mMessage.setText("");
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode != RESULT_CANCELED && requestCode == REQUEST_DISCOVERABLE) {
            mAcceptThread = new AcceptThread();
            mAcceptThread.start();
            Toast.makeText(this, "Searching for users...", Toast.LENGTH_SHORT).show();
        } else if (resultCode == RESULT_OK && requestCode == PICK_IMAGE) {
            Uri image = data.getData();
            String[] filePathColumn = {MediaStore.Images.Media.DATA};
            Cursor cursor = getContentResolver().query(image, filePathColumn, null, null, null);

            cursor.moveToFirst();
            int columnIndex = cursor.getColumnIndex(filePathColumn[0]);
            String picturePath = cursor.getString(columnIndex);

            new SendImageThread(picturePath).start();
            cursor.close();
        } else if (requestCode == REQUEST_DISCOVERABLE) {
            Toast.makeText(this, "New users cannot join your chat room", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if (mAcceptThread != null) {
            mAcceptThread.cancel();
        }

        if (mSockets != null) {
            for (BluetoothSocket socket : mSockets) {
                try {
                    socket.close();
                } catch (IOException e) {
                    System.err.println("Failed to close socket");
                    System.err.println(e.toString());
                }
            }
        }
    }

    private void manageSocket(BluetoothSocket socket) {
        mChatManager.startConnection(socket);
        mSockets.add(socket);
        byte[] byteArray;

        byteArray = mChatManager.buildPacket(
                ChatManager.MESSAGE_NAME,
                mUsername,
                mChatRoomName.getBytes()
        );

        Toast.makeText(this, "User connected", Toast.LENGTH_SHORT).show();
        mChatManager.writeChatRoomName(byteArray);
    }

    private class SendImageThread extends Thread {

        private Bitmap bitmap;

        public SendImageThread(String picturePath) {
            this.bitmap = BitmapFactory.decodeFile(picturePath);
        }

        public void run() {
            if (bitmap == null) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(getBaseContext(), "Image is incompatible or not locally stored", Toast.LENGTH_SHORT).show();
                    }
                });

                return;
            }

            if (bitmap.getWidth() > 1024 || bitmap.getHeight() > 1024) {
                float scalingFactor;

                if (bitmap.getWidth() >= bitmap.getHeight()) {
                    scalingFactor = 1024f / bitmap.getWidth();
                } else {
                    Matrix fixRotation = new Matrix();
                    fixRotation.postRotate(90);
                    scalingFactor = 1024f / bitmap.getHeight();
                }

                bitmap = Bitmap.createScaledBitmap(
                        bitmap,
                        (int) (bitmap.getWidth() * scalingFactor),
                        (int) (bitmap.getHeight() * scalingFactor),
                        false
                );
            }

            try {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.JPEG, 15, output);
                byte[] imageBytes = output.toByteArray();
                byte[] packet = mChatManager.buildPacket(
                        ChatManager.MESSAGE_SEND_IMAGE,
                        mUsername,
                        imageBytes
                );
                mChatManager.writeMessage(packet);
            } catch (Exception e) {
                System.err.println("Failed to send image");
                System.err.println(e.toString());
            }
        }

    }

    private class AcceptThread extends Thread {

        private final BluetoothServerSocket mmServerSocket;
        private boolean isAccepting;

        public AcceptThread() {
            BluetoothServerSocket tmp = null;
            isAccepting = true;

            try {
                tmp = mBluetoothAdapter.
                        listenUsingRfcommWithServiceRecord(
                                mChatRoomName, java.util.UUID.fromString(MainActivity.UUID)
                        );
            } catch (IOException e) {
                System.err.println("Failed to set up Accept Thread");
                System.err.println(e.toString());
            }

            mmServerSocket = tmp;
        }

        public void run() {
            while (isAccepting) {
                final BluetoothSocket socket;

                try {
                    socket = mmServerSocket.accept();
                } catch (IOException e) {
                    break;
                }

                if (socket != null) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            manageSocket(socket);
                        }
                    });
                }
            }
        }

        public void cancel() {
            try {
                isAccepting = false;
                mmServerSocket.close();
            } catch (IOException e) {
                System.err.println(e.toString());
            }
        }

    }

    //---------------------GPS用--------

    private void requestLocationUpdates() {
        Log.e(TAG, "requestLocationUpdates()");
        boolean isNetworkEnabled = mLocationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        if (isNetworkEnabled) {
            mLocationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    LOCATION_UPDATE_MIN_TIME,
                    LOCATION_UPDATE_MIN_DISTANCE,
                    this);
            Location location = mLocationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (location != null) {
                setLocationToEditText();
            }
        } else {
            String message = "Networkが無効になっています。";
            showMessage(message);
        }
    }

    // Called when the location has changed.
    @Override
    public void onLocationChanged(Location location) {
        Log.e(TAG, "onLocationChanged.");
//        showLocation(location);
    }

    // Called when the provider status changed.
    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
        Log.e(TAG, "onStatusChanged.");
        switch (status) {
            case LocationProvider.OUT_OF_SERVICE:
                // if the provider is out of service, and this is not expected to change in the near future.
                String outOfServiceMessage = provider +"が圏外になっていて取得できません。";
                showMessage(outOfServiceMessage);
                break;
            case LocationProvider.TEMPORARILY_UNAVAILABLE:
                // if the provider is temporarily unavailable but is expected to be available shortly.
                String temporarilyUnavailableMessage = "一時的に" + provider + "が利用できません。もしかしたらすぐに利用できるようになるかもです。";
                showMessage(temporarilyUnavailableMessage);
                break;
            case LocationProvider.AVAILABLE:
                // if the provider is currently available.
                if (provider.equals(LocationManager.NETWORK_PROVIDER)) {
                    String availableMessage = provider + "が利用可能になりました。";
                    showMessage(availableMessage);
                }
                break;
        }
    }

    // Called when the provider is enabled by the user.
    @Override
    public void onProviderEnabled(String provider) {
        Log.e(TAG, "onProviderEnabled.");
        String message = provider + "が有効になりました。";
        showMessage(message);
        if (provider.equals(LocationManager.NETWORK_PROVIDER)) {
        }
    }

    // Called when the provider is disabled by the user.
    @Override
    public void onProviderDisabled(String provider) {
        Log.e(TAG, "onProviderDisabled.");
        if (provider.equals(LocationManager.NETWORK_PROVIDER)) {
            String message = provider + "が無効になってしまいました。";
            showMessage(message);
        }
    }

    private String getLocationMessage(Location location) {
        double longitude = location.getLongitude();
        double latitude = location.getLatitude();
        long time = location.getTime();
        Date date = new Date(time);
        DateFormat formatter = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss:SSS");
        String dateFormatted = formatter.format(date);

        String logitudeString = "Longitude : " + String.valueOf(longitude);
        String latitudeString = "Latitude : " + String.valueOf(latitude);
        String timeString = "取得時間 : " + dateFormatted;

        String message = logitudeString + "\n" + latitudeString + "\n" + timeString;
        return message;
    }

    private void showMessage(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    //---------------------GPSここまで---

}
