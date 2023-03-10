/*
 * Copyright 2017, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.managedprovisioning.model;

import static android.app.admin.DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Color;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import com.android.managedprovisioning.common.Utils;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@SmallTest
@RunWith(MockitoJUnitRunner.class)
public class CustomizationParamsTest {
    private static final Context mContext = InstrumentationRegistry.getTargetContext();
    private static final ComponentName COMPONENT_NAME = new ComponentName("org.test", "ATestDPC");
    private static final String SAMPLE_URL = "http://d.android.com";
    private static final int DEFAULT_LOGO_COLOR = Color.rgb(99, 99, 99);

    @Mock
    private Utils mUtils;

    @Before
    public void setup() {
        when(mUtils.getAccentColor(any())).thenReturn(DEFAULT_LOGO_COLOR);
    }

    @Test
    public void respectsUrl() {
        // given
        ProvisioningParams params = createParams(null, SAMPLE_URL, null);

        // when
        CustomizationParams instance = createInstance(params);

        // then
        assertThat(instance.supportUrl, equalTo(SAMPLE_URL));
    }

    @Test
    public void urlDefaultsToNull() {
        // given
        ProvisioningParams params = createParams(null, null, null);

        // when
        CustomizationParams instance = createInstance(params);

        // then
        assertThat(instance.supportUrl, nullValue());
    }

    @Test
    public void ignoresInvalidUrl() {
        // given
        ProvisioningParams params = createParams(null, "not a valid web url", null);

        // when
        CustomizationParams instance = createInstance(params);

        // then
        assertThat(instance.supportUrl, nullValue());
    }

    private CustomizationParams createInstance(ProvisioningParams params) {
        return CustomizationParams.createInstance(params, mContext, mUtils);
    }

    private ProvisioningParams createParams(
            String provisioningAction, String supportUrl, String orgName) {
        ProvisioningParams.Builder builder =
                new ProvisioningParams.Builder().setDeviceAdminComponentName(COMPONENT_NAME);

        builder.setProvisioningAction(provisioningAction == null ? ACTION_PROVISION_MANAGED_DEVICE
                : provisioningAction);

        if (supportUrl != null) {
            builder.setSupportUrl(supportUrl);
        }

        if (orgName != null) {
            builder.setOrganizationName(orgName);
        }

        return builder.build();
    }

    private int getColor(int colorId) {
        return mContext.getColor(colorId);
    }
}
