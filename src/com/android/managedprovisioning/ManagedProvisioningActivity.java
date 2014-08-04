/*
 * Copyright 2014, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.managedprovisioning;

import static android.app.admin.DevicePolicyManager.EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_NAME;
import static com.android.managedprovisioning.EncryptDeviceActivity.EXTRA_RESUME;
import static com.android.managedprovisioning.EncryptDeviceActivity.EXTRA_RESUME_TARGET;
import static com.android.managedprovisioning.EncryptDeviceActivity.TARGET_PROFILE_OWNER;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.UserInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.support.v4.content.LocalBroadcastManager;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Button;

import java.util.List;

/**
 * Managed provisioning sets up a separate profile on a device whose primary user is already set up.
 * The typical example is setting up a corporate profile that is controlled by their employer on a
 * users personal device to keep personal and work data separate.
 *
 * The activity handles the input validation and UI for managed profile provisioning.
 * and starts the {@link ManagedProvisioningService}, which runs through the setup steps in an
 * async task.
 */
// TODO: Proper error handling to report back to the user and potentially the mdm.
public class ManagedProvisioningActivity extends Activity {

    private static final String MANAGE_USERS_PERMISSION = "android.permission.MANAGE_USERS";

    protected static final String EXTRA_USER_HAS_CONSENTED_PROVISIONING =
            "com.android.managedprovisioning.EXTRA_USER_HAS_CONSENTED_PROVISIONING";

    // TODO remove these when the new constant values are in use in all relevant places.
    protected static final String EXTRA_LEGACY_PROVISIONING_DEVICE_ADMIN_PACKAGE_NAME =
            "deviceAdminPackageName";
    protected static final String EXTRA_LEGACY_PROVISIONING_DEFAULT_MANAGED_PROFILE_NAME =
            "defaultManagedProfileName";

    // Aliases to start managed provisioning with and without MANAGE_USERS permission
    protected static final ComponentName ALIAS_CHECK_CALLER =
            new ComponentName("com.android.managedprovisioning",
                    "com.android.managedprovisioning.ManagedProvisioningActivity");

    protected static final ComponentName ALIAS_NO_CHECK_CALLER =
            new ComponentName("com.android.managedprovisioning",
                    "com.android.managedprovisioning.ManagedProvisioningActivityNoCallerCheck");

    protected static final int ENCRYPT_DEVICE_REQUEST_CODE = 2;

    private String mMdmPackageName;
    private BroadcastReceiver mServiceMessageReceiver;
    private boolean mUserConsented;

    private View mContentView;
    private View mMainTextView;
    private View mProgressView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ProvisionLogger.logd("Managed provisioning activity ONCREATE");

        PackageManager pm = getPackageManager();
        if (!pm.hasSystemFeature(PackageManager.FEATURE_MANAGED_PROFILES)) {
            showErrorAndClose(R.string.managed_provisioning_not_supported,
                    "Exiting managed provisioning, managed profiles feature is not available");
            return;
        }
        if (Process.myUserHandle().getIdentifier() != UserHandle.USER_OWNER) {
            showErrorAndClose(R.string.user_is_not_owner,
                    "Exiting managed provisioning, calling user is not owner.");
            return;
        }

        // Setup broadcast receiver for feedback from service.
        mServiceMessageReceiver = new ServiceMessageReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction(ManagedProvisioningService.ACTION_PROVISIONING_SUCCESS);
        filter.addAction(ManagedProvisioningService.ACTION_PROVISIONING_ERROR);
        LocalBroadcastManager.getInstance(this).registerReceiver(mServiceMessageReceiver, filter);

        // Initialize member variables from the intent, stop if the intent wasn't valid.
        try {
            initialize(getIntent());
        } catch (ManagedProvisioningFailedException e) {
            showErrorAndClose(R.string.managed_provisioning_error_text, e.getMessage());
            return;
        }

        final LayoutInflater inflater = getLayoutInflater();
        mContentView = inflater.inflate(R.layout.user_consent, null);
        mMainTextView = mContentView.findViewById(R.id.main_text_container);
        mProgressView = mContentView.findViewById(R.id.progress_container);
        setContentView(mContentView);
        setMdmIcon(mMdmPackageName, mContentView);

        // If the caller started us via ALIAS_NO_CHECK_CALLER then they must have permission to
        // MANAGE_USERS since it is a restricted intent. Otherwise, check the calling package.
        boolean hasManageUsersPermission = (getComponentName().equals(ALIAS_NO_CHECK_CALLER));
        if (!hasManageUsersPermission) {
            // Calling package has to equal the requested device admin package or has to be system.
            String callingPackage = getCallingPackage();
            if (callingPackage == null) {
                showErrorAndClose(R.string.managed_provisioning_error_text,
                        "Calling package is null. " +
                        "Was startActivityForResult used to start this activity?");
                return;
            }
            if (!callingPackage.equals(mMdmPackageName)
                    && !packageHasManageUsersPermission(callingPackage)) {
                showErrorAndClose(R.string.managed_provisioning_error_text, "Permission denied, "
                        + "calling package tried to set a different package as profile owner. "
                        + "The system MANAGE_USERS permission is required.");
                return;
            }
        }

        // Don't continue if the caller tries to skip user consent without permission.
        // Only system apps with the MANAGE_USERS permission can claim that the user consented.
        if (getIntent().hasExtra(EXTRA_USER_HAS_CONSENTED_PROVISIONING)) {
            if (!hasManageUsersPermission) {
                showErrorAndClose(R.string.managed_provisioning_error_text, "Permission denied, "
                        + "you need MANAGE_USERS permission to skip user consent.");
                return;
            }
            mUserConsented = true;
        }

        // If there is already a managed profile, allow the user to cancel or delete it.
        int existingManagedProfileUserId = alreadyHasManagedProfile();
        if (existingManagedProfileUserId != -1) {
            showManagedProfileExistsDialog(existingManagedProfileUserId);
        } else {
            showStartProvisioningScreen();
        }
    }

    private void showStartProvisioningScreen() {
        // Skip the user consent if user has previously consented.
        if (mUserConsented) {
            checkEncryptedAndStartProvisioningService();
        } else {
            Button positiveButton = (Button) mContentView.findViewById(R.id.positive_button);
            positiveButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    mUserConsented = true;
                    checkEncryptedAndStartProvisioningService();
                }
            });
        }
    }

    private boolean packageHasManageUsersPermission(String pkg) {
        int packagePermission = this.getPackageManager()
                .checkPermission(MANAGE_USERS_PERMISSION, pkg);
        if (packagePermission == PackageManager.PERMISSION_GRANTED) {
            return true;
        } else {
            return false;
        }
    }

    class ServiceMessageReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(ManagedProvisioningService.ACTION_PROVISIONING_SUCCESS)) {
                ProvisionLogger.logd("Successfully provisioned");
                ManagedProvisioningActivity.this.setResult(Activity.RESULT_OK);
                ManagedProvisioningActivity.this.finish();
                return;
            } else if (action.equals(ManagedProvisioningService.ACTION_PROVISIONING_ERROR)) {
                String errorLogMessage = intent.getStringExtra(
                        ManagedProvisioningService.EXTRA_LOG_MESSAGE_KEY);
                ProvisionLogger.logd("Error reported: " + errorLogMessage);
                showErrorAndClose(R.string.managed_provisioning_error_text, errorLogMessage);
                return;
            }
        }
    }

    private void setMdmIcon(String packageName, View contentView) {
        if (packageName != null) {
            PackageManager pm = getPackageManager();
            try {
                ApplicationInfo ai = pm.getApplicationInfo(packageName, /* default flags */ 0);
                if (ai != null) {
                    Drawable packageIcon = pm.getApplicationIcon(packageName);
                    ImageView imageView = (ImageView) contentView.findViewById(R.id.mdm_icon_view);
                    imageView.setImageDrawable(packageIcon);

                    String appLabel = pm.getApplicationLabel(ai).toString();
                    TextView deviceManagerName = (TextView) contentView
                            .findViewById(R.id.device_manager_name);
                    deviceManagerName.setText(appLabel);
                }
            } catch (PackageManager.NameNotFoundException e) {
                // Package does not exist, ignore. Should never happen.
                ProvisionLogger.loge("Package does not exist. Should never happen.");
            }
        }
    }

    /**
     * Checks if all required provisioning parameters are provided.
     * Does not check for extras that are optional such as the email address.
     *
     * @param intent The intent that started provisioning
     */
    private void initialize(Intent intent) throws ManagedProvisioningFailedException {

        // Validate package name and check if the package is installed
        mMdmPackageName = getMdmPackageName(intent);
        if (TextUtils.isEmpty(mMdmPackageName)) {
            throw new ManagedProvisioningFailedException("Missing intent extra: "
                    + EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_NAME);
        } else {
            try {
                this.getPackageManager().getPackageInfo(mMdmPackageName, 0);
            } catch (NameNotFoundException e) {
                throw new ManagedProvisioningFailedException("Mdm "+ mMdmPackageName
                        + " is not installed. " + e);
            }
        }
    }

    private String getMdmPackageName(Intent intent) {
        String name = intent.getStringExtra(EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_NAME);
        if (TextUtils.isEmpty(name)) {
            name = intent.getStringExtra(EXTRA_LEGACY_PROVISIONING_DEVICE_ADMIN_PACKAGE_NAME);
        }
        return name;
    }

    @Override
    public void onBackPressed() {
        // TODO: Handle this graciously by stopping the provisioning flow and cleaning up.
    }

    @Override
    public void onDestroy() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mServiceMessageReceiver);
        super.onDestroy();
    }

    /**
     * If the device is encrypted start the service which does the provisioning, otherwise ask for
     * user consent to encrypt the device.
     */
    private void checkEncryptedAndStartProvisioningService() {
        if (EncryptDeviceActivity.isDeviceEncrypted()
                || SystemProperties.getBoolean("persist.sys.no_req_encrypt", false)) {
            // Remove any pre-provisioning UI in favour of progress display
            BootReminder.cancelProvisioningReminder(this);
            mProgressView.setVisibility(View.VISIBLE);
            mMainTextView.setVisibility(View.GONE);

            Intent intent = new Intent(this, ManagedProvisioningService.class);
            intent.putExtras(getIntent());
            startService(intent);
        } else {
            Bundle resumeExtras = getIntent().getExtras();
            resumeExtras.putBoolean(EXTRA_USER_HAS_CONSENTED_PROVISIONING, mUserConsented);
            resumeExtras.putString(EXTRA_RESUME_TARGET, TARGET_PROFILE_OWNER);
            Intent encryptIntent = new Intent(this, EncryptDeviceActivity.class)
                    .putExtra(EXTRA_RESUME, resumeExtras);
            startActivityForResult(encryptIntent, ENCRYPT_DEVICE_REQUEST_CODE);
            // Continue in onActivityResult or after reboot.
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == ENCRYPT_DEVICE_REQUEST_CODE) {
            if (resultCode == RESULT_CANCELED) {
                ProvisionLogger.loge("User canceled device encryption.");
                setResult(Activity.RESULT_CANCELED);
                finish();
            }
        }
    }

    public void showErrorAndClose(int resourceId, String logText) {
        ProvisionLogger.loge(logText);
        new ManagedProvisioningErrorDialog(getString(resourceId))
              .show(getFragmentManager(), "ErrorDialogFragment");
    }

    /**
     * @return The User id of an already existing managed profile or -1 if none
     * exists
     */
    int alreadyHasManagedProfile() {
        UserManager userManager = (UserManager) getSystemService(Context.USER_SERVICE);
        List<UserInfo> profiles = userManager.getProfiles(getUserId());
        for (UserInfo userInfo : profiles) {
            if (userInfo.isManagedProfile()) {
                return userInfo.getUserHandle().getIdentifier();
            }
        }
        return -1;
    }

    /**
     * Builds a dialog that allows the user to remove an existing managed profile after they were
     * shown an additional warning.
     */
    private void showManagedProfileExistsDialog(
            final int existingManagedProfileUserId) {

        // Before deleting, show a warning dialog
        DialogInterface.OnClickListener warningListener =
                new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                // Really delete the profile if the user clicks delete on the warning dialog.
                final DialogInterface.OnClickListener deleteListener =
                        new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        UserManager userManager =
                                (UserManager) getSystemService(Context.USER_SERVICE);
                        userManager.removeUser(existingManagedProfileUserId);
                        showStartProvisioningScreen();
                    }
                };
                buildDeleteManagedProfileDialog(
                        getString(R.string.sure_you_want_to_delete_profile),
                        deleteListener).show();
            }
        };

        buildDeleteManagedProfileDialog(
                getString(R.string.managed_profile_already_present),
                warningListener).show();
    }

    private AlertDialog buildDeleteManagedProfileDialog(String message,
            DialogInterface.OnClickListener deleteListener) {
        DialogInterface.OnClickListener cancelListener =
                new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                ManagedProvisioningActivity.this.finish();
            }
        };

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setMessage(message)
        .setPositiveButton(getString(R.string.delete_profile),
                deleteListener)
                .setNegativeButton(getString(R.string.cancel_delete_profile),
                        cancelListener);

        return builder.create();
    }
    /**
     * Exception thrown when the managed provisioning has failed completely.
     *
     * We're using a custom exception to avoid catching subsequent exceptions that might be
     * significant.
     */
    private class ManagedProvisioningFailedException extends Exception {
      public ManagedProvisioningFailedException(String message) {
          super(message);
      }
    }
}

