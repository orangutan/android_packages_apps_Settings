/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.settings.net;

import static android.net.NetworkPolicy.LIMIT_DISABLED;
import static android.net.NetworkPolicy.WARNING_DISABLED;
import static android.net.NetworkTemplate.MATCH_MOBILE_3G_LOWER;
import static android.net.NetworkTemplate.MATCH_MOBILE_4G;
import static android.net.NetworkTemplate.MATCH_MOBILE_ALL;
import static com.android.internal.util.Preconditions.checkNotNull;

import android.net.INetworkPolicyManager;
import android.net.NetworkPolicy;
import android.net.NetworkTemplate;
import android.os.AsyncTask;
import android.os.RemoteException;

import com.android.internal.util.Objects;
import com.google.android.collect.Lists;

import java.util.ArrayList;

/**
 * Utility class to modify list of {@link NetworkPolicy}. Specifically knows
 * about which policies can coexist.
 */
public class NetworkPolicyEditor {
    // TODO: be more robust when missing policies from service

    private INetworkPolicyManager mPolicyService;
    private ArrayList<NetworkPolicy> mPolicies = Lists.newArrayList();

    public NetworkPolicyEditor(INetworkPolicyManager policyService) {
        mPolicyService = checkNotNull(policyService);
    }

    public void read() {
        try {
            final NetworkPolicy[] policies = mPolicyService.getNetworkPolicies();
            mPolicies.clear();
            for (NetworkPolicy policy : policies) {
                // TODO: find better place to clamp these
                if (policy.limitBytes < -1) {
                    policy.limitBytes = LIMIT_DISABLED;
                }
                if (policy.warningBytes < -1) {
                    policy.warningBytes = WARNING_DISABLED;
                }

                mPolicies.add(policy);
            }
        } catch (RemoteException e) {
            throw new RuntimeException("problem reading policies", e);
        }
    }

    public void writeAsync() {
        // TODO: consider making more robust by passing through service
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                write();
                return null;
            }
        }.execute();
    }

    public void write() {
        try {
            final NetworkPolicy[] policies = mPolicies.toArray(new NetworkPolicy[mPolicies.size()]);
            mPolicyService.setNetworkPolicies(policies);
        } catch (RemoteException e) {
            throw new RuntimeException("problem reading policies", e);
        }
    }

    public NetworkPolicy getPolicy(NetworkTemplate template) {
        for (NetworkPolicy policy : mPolicies) {
            if (policy.template.equals(template)) {
                return policy;
            }
        }
        return null;
    }

    public void setPolicyCycleDay(NetworkTemplate template, int cycleDay) {
        getPolicy(template).cycleDay = cycleDay;
        writeAsync();
    }

    public void setPolicyWarningBytes(NetworkTemplate template, long warningBytes) {
        getPolicy(template).warningBytes = warningBytes;
        writeAsync();
    }

    public void setPolicyLimitBytes(NetworkTemplate template, long limitBytes) {
        getPolicy(template).limitBytes = limitBytes;
        writeAsync();
    }

    public boolean isMobilePolicySplit(String subscriberId) {
        boolean has3g = false;
        boolean has4g = false;
        for (NetworkPolicy policy : mPolicies) {
            final NetworkTemplate template = policy.template;
            if (Objects.equal(subscriberId, template.getSubscriberId())) {
                switch (template.getMatchRule()) {
                    case MATCH_MOBILE_3G_LOWER:
                        has3g = true;
                        break;
                    case MATCH_MOBILE_4G:
                        has4g = true;
                        break;
                }
            }
        }
        return has3g && has4g;
    }

    public void setMobilePolicySplit(String subscriberId, boolean split) {
        final boolean beforeSplit = isMobilePolicySplit(subscriberId);

        final NetworkTemplate template3g = new NetworkTemplate(MATCH_MOBILE_3G_LOWER, subscriberId);
        final NetworkTemplate template4g = new NetworkTemplate(MATCH_MOBILE_4G, subscriberId);
        final NetworkTemplate templateAll = new NetworkTemplate(MATCH_MOBILE_ALL, subscriberId);

        if (split == beforeSplit) {
            // already in requested state; skip
            return;

        } else if (beforeSplit && !split) {
            // combine, picking most restrictive policy
            final NetworkPolicy policy3g = getPolicy(template3g);
            final NetworkPolicy policy4g = getPolicy(template4g);

            final NetworkPolicy restrictive = policy3g.compareTo(policy4g) < 0 ? policy3g
                    : policy4g;
            mPolicies.remove(policy3g);
            mPolicies.remove(policy4g);
            mPolicies.add(
                    new NetworkPolicy(templateAll, restrictive.cycleDay, restrictive.warningBytes,
                            restrictive.limitBytes));
            writeAsync();

        } else if (!beforeSplit && split) {
            // duplicate existing policy into two rules
            final NetworkPolicy policyAll = getPolicy(templateAll);
            mPolicies.remove(policyAll);
            mPolicies.add(new NetworkPolicy(
                    template3g, policyAll.cycleDay, policyAll.warningBytes, policyAll.limitBytes));
            mPolicies.add(new NetworkPolicy(
                    template4g, policyAll.cycleDay, policyAll.warningBytes, policyAll.limitBytes));
            writeAsync();

        }
    }

}
