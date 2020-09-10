package pan.alexander.tordnscrypt.settings.tor_apps;
/*
    This file is part of InviZible Pro.

    InviZible Pro is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    InviZible Pro is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with InviZible Pro.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2019-2020 by Garmatin Oleksandr invizible.soft@gmail.com
*/

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.LinearLayoutCompat;
import androidx.appcompat.widget.SearchView;
import androidx.appcompat.widget.SwitchCompat;
import androidx.cardview.widget.CardView;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.FutureTask;

import pan.alexander.tordnscrypt.R;
import pan.alexander.tordnscrypt.dialogs.NotificationHelper;
import pan.alexander.tordnscrypt.modules.ModulesStatus;
import pan.alexander.tordnscrypt.settings.PathVars;
import pan.alexander.tordnscrypt.utils.CachedExecutor;
import pan.alexander.tordnscrypt.utils.InstalledApplications;
import pan.alexander.tordnscrypt.utils.PrefManager;
import pan.alexander.tordnscrypt.utils.TorRefreshIPsWork;
import pan.alexander.tordnscrypt.utils.Verifier;
import pan.alexander.tordnscrypt.utils.file_operations.FileOperations;

import static pan.alexander.tordnscrypt.TopFragment.TOP_BROADCAST;
import static pan.alexander.tordnscrypt.TopFragment.appSign;
import static pan.alexander.tordnscrypt.TopFragment.wrongSign;
import static pan.alexander.tordnscrypt.utils.RootExecService.LOG_TAG;
import static pan.alexander.tordnscrypt.utils.enums.OperationMode.ROOT_MODE;
import static pan.alexander.tordnscrypt.utils.enums.OperationMode.VPN_MODE;


public class UnlockTorAppsFragment extends Fragment implements InstalledApplications.OnAppAddListener,
        CompoundButton.OnCheckedChangeListener, SearchView.OnQueryTextListener {
    private Set<String> setUnlockApps;
    private RecyclerView.Adapter<TorAppsAdapter.TorAppsViewHolder> mAdapter;
    private ProgressBar pbTorApp;
    private String unlockAppsStr;
    private volatile CopyOnWriteArrayList<ApplicationData> appsUnlock;
    private CopyOnWriteArrayList<ApplicationData> savedAppsUnlockWhenSearch = null;
    private FutureTask<?> futureTask;
    private Handler handler;


    public UnlockTorAppsFragment() {
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {

        View view = inflater.inflate(R.layout.fragment_preferences_tor_apps, container, false);

        ((SwitchCompat) view.findViewById(R.id.swTorAppSellectorAll)).setOnCheckedChangeListener(this);
        ((SearchView) view.findViewById(R.id.searhTorApp)).setOnQueryTextListener(this);
        pbTorApp = view.findViewById(R.id.pbTorApp);

        return view;
    }


    @Override
    public void onResume() {
        super.onResume();

        Context context = getActivity();

        if (context == null) {
            return;
        }

        ////////////////////////////////////////////////////////////////////////////////////
        ///////////////////////Reverse logic when route all through Tor!///////////////////
        //////////////////////////////////////////////////////////////////////////////////

        Looper looper = Looper.getMainLooper();
        if (looper != null) {
            handler = new Handler(looper);
        }

        appsUnlock = new CopyOnWriteArrayList<>();

        SharedPreferences shPref = PreferenceManager.getDefaultSharedPreferences(context);
        boolean routeAllThroughTorDevice = shPref.getBoolean("pref_fast_all_through_tor", true);
        boolean bypassAppsProxy = getArguments() != null && getArguments().getBoolean("proxy");

        if (bypassAppsProxy) {
            requireActivity().setTitle(R.string.proxy_exclude_apps_from_proxy);
            unlockAppsStr = "clearnetAppsForProxy";
        } else if (!routeAllThroughTorDevice) {
            requireActivity().setTitle(R.string.pref_tor_unlock_app);
            unlockAppsStr = "unlockApps";
        } else {
            requireActivity().setTitle(R.string.pref_tor_clearnet_app);
            unlockAppsStr = "clearnetApps";
        }

        setUnlockApps = new PrefManager(context).getSetStrPref(unlockAppsStr);

        RecyclerView rvListTorApps = getActivity().findViewById(R.id.rvTorApps);
        rvListTorApps.requestFocus();
        RecyclerView.LayoutManager mLayoutManager = new LinearLayoutManager(context);
        rvListTorApps.setLayoutManager(mLayoutManager);

        mAdapter = new TorAppsAdapter();
        rvListTorApps.setAdapter(mAdapter);

        getDeviceApps(context, setUnlockApps);

        CachedExecutor.INSTANCE.getExecutorService().submit(() -> {
            try {
                Verifier verifier = new Verifier(context);
                String appSignAlt = verifier.getApkSignature();
                if (!verifier.decryptStr(wrongSign, appSign, appSignAlt).equals(TOP_BROADCAST)) {
                    NotificationHelper notificationHelper = NotificationHelper.setHelperMessage(
                            context, getString(R.string.verifier_error), "11");
                    if (notificationHelper != null && isAdded()) {
                        notificationHelper.show(getParentFragmentManager(), NotificationHelper.TAG_HELPER);
                    }
                }

            } catch (Exception e) {
                NotificationHelper notificationHelper = NotificationHelper.setHelperMessage(
                        context, getString(R.string.verifier_error), "188");
                if (notificationHelper != null && isAdded()) {
                    notificationHelper.show(getParentFragmentManager(), NotificationHelper.TAG_HELPER);
                }
                Log.e(LOG_TAG, "UnlockTorAppsFragment fault " + e.getMessage() + " " + e.getCause() + System.lineSeparator() +
                        Arrays.toString(e.getStackTrace()));
            }
        });
    }

    @Override
    public void onStop() {
        super.onStop();

        Context context = getActivity();

        if (context== null) {
            return;
        }

        if (futureTask != null && futureTask.cancel(true)) {
            return;
        }

        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }

        PathVars pathVars = PathVars.getInstance(context);
        String appDataDir = pathVars.getAppDataDir();

        if (savedAppsUnlockWhenSearch != null) {
            appsUnlock = savedAppsUnlockWhenSearch;
        }

        Set<String> setAppUIDtoSave = new HashSet<>();
        for (int i = 0; i < appsUnlock.size(); i++) {
            ApplicationData app = appsUnlock.get(i);
            if (app.getActive())
                setAppUIDtoSave.add(String.valueOf(app.getUid()));
        }

        if (setAppUIDtoSave.equals(setUnlockApps)) {
            return;
        }

        new PrefManager(context).setSetStrPref(unlockAppsStr, setAppUIDtoSave);

        List<String> listAppUIDtoSave = new LinkedList<>();

        for (String appUID: setAppUIDtoSave) {
            if (Integer.parseInt(appUID) >= 0) {
                listAppUIDtoSave.add(appUID);
            }
        }

        FileOperations.writeToTextFile(context, appDataDir + "/app_data/tor/" + unlockAppsStr, listAppUIDtoSave, "ignored");
        Toast.makeText(context, getString(R.string.toastSettings_saved), Toast.LENGTH_SHORT).show();

        ModulesStatus modulesStatus = ModulesStatus.getInstance();
        if (modulesStatus.getMode() == ROOT_MODE) {
            /////////////Refresh iptables rules/////////////////////////
            TorRefreshIPsWork torRefreshIPsWork = new TorRefreshIPsWork(context, null);
            torRefreshIPsWork.refreshIPs();
        } else if (modulesStatus.getMode() == VPN_MODE) {
            modulesStatus.setIptablesRulesUpdateRequested(context, true);
        }
    }

    @Override
    public void onCheckedChanged(CompoundButton compoundButton, boolean active) {
        if (compoundButton.getId() == R.id.swTorAppSellectorAll && mAdapter != null && appsUnlock != null) {
            if (active) {
                for (int i = 0; i < appsUnlock.size(); i++) {
                    ApplicationData app = appsUnlock.get(i);
                    app.setActive(true);
                    appsUnlock.set(i, app);
                }
            } else {
                for (int i = 0; i < appsUnlock.size(); i++) {
                    ApplicationData app = appsUnlock.get(i);
                    app.setActive(false);
                    appsUnlock.set(i, app);
                }
            }
            mAdapter.notifyDataSetChanged();
        }
    }

    @Override
    public boolean onQueryTextSubmit(String s) {
        return false;
    }

    @Override
    public boolean onQueryTextChange(String s) {

        if (s == null || s.isEmpty()) {
            if (savedAppsUnlockWhenSearch != null) {
                appsUnlock = savedAppsUnlockWhenSearch;
                savedAppsUnlockWhenSearch = null;
                mAdapter.notifyDataSetChanged();
            }
            return true;
        }

        if (savedAppsUnlockWhenSearch == null) {
            savedAppsUnlockWhenSearch = new CopyOnWriteArrayList<>(appsUnlock);
        }

        appsUnlock.clear();

        for (int i = 0; i < savedAppsUnlockWhenSearch.size(); i++) {
            ApplicationData app = savedAppsUnlockWhenSearch.get(i);
            if (app.toString().toLowerCase().contains(s.toLowerCase().trim())
                    || app.getPack().toLowerCase().contains(s.toLowerCase().trim())) {
                appsUnlock.add(app);
            }
        }
        mAdapter.notifyDataSetChanged();

        return true;
    }

    @Override
    public void onAppAdded(@NotNull ApplicationData application) {
        appsUnlock.add(0, application);
        handler.post(() -> mAdapter.notifyDataSetChanged());
    }

    private class TorAppsAdapter extends RecyclerView.Adapter<TorAppsAdapter.TorAppsViewHolder> {
        LayoutInflater lInflater = (LayoutInflater) requireActivity().getSystemService(Context.LAYOUT_INFLATER_SERVICE);

        TorAppsAdapter() {
        }

        @NonNull
        @Override
        public TorAppsAdapter.TorAppsViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int i) {
            View view = lInflater.inflate(R.layout.item_tor_app, parent, false);
            return new TorAppsViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull TorAppsViewHolder torAppsViewHolder, int position) {
            torAppsViewHolder.bind(position);
        }

        @Override
        public int getItemCount() {
            return appsUnlock.size();
        }

        ApplicationData getItem(int position) {
            return appsUnlock.get(position);
        }

        void setActive(int position, boolean active) {

            ApplicationData appUnlock = appsUnlock.get(position);
            appUnlock.setActive(active);
            appsUnlock.set(position, appUnlock);

            if (savedAppsUnlockWhenSearch != null) {
                for (int i = 0; i < savedAppsUnlockWhenSearch.size(); i++) {
                    ApplicationData appUnlockSaved = savedAppsUnlockWhenSearch.get(i);
                    if (appUnlockSaved.equals(appUnlock)) {
                        appUnlockSaved.setActive(active);
                        savedAppsUnlockWhenSearch.set(i, appUnlockSaved);
                    }
                }
            }
        }

        private class TorAppsViewHolder extends RecyclerView.ViewHolder {
            private Activity activity;
            private ImageView imgTorApp;
            private TextView tvTorAppName;
            private ColorStateList tvTorAppNameColors;
            private TextView tvTorAppPackage;
            private SwitchCompat swTorApp;
            private LinearLayoutCompat lLayoutTorApps;
            private CardView cardTorAppFragment;

            private TorAppsViewHolder(View itemView) {
                super(itemView);

                activity = getActivity();
                imgTorApp = itemView.findViewById(R.id.imgTorApp);
                tvTorAppName = itemView.findViewById(R.id.tvTorAppName);
                tvTorAppNameColors = tvTorAppName.getTextColors();
                tvTorAppPackage = itemView.findViewById(R.id.tvTorAppPackage);
                swTorApp = itemView.findViewById(R.id.swTorApp);
                CardView cardTorApps = itemView.findViewById(R.id.cardTorApp);
                cardTorApps.setFocusable(true);
                lLayoutTorApps = itemView.findViewById(R.id.llayoutTorApps);

                if (activity != null) {
                    cardTorAppFragment = activity.findViewById(R.id.cardTorAppFragment);
                }

                View.OnClickListener onClickListener = view -> {
                    int appPosition = getAdapterPosition();
                    boolean appActive = getItem(appPosition).getActive();
                    setActive(appPosition, !appActive);
                    mAdapter.notifyDataSetChanged();
                };

                swTorApp.setFocusable(false);
                swTorApp.setOnClickListener(onClickListener);

                cardTorApps.setOnClickListener(onClickListener);

                View.OnFocusChangeListener onFocusChangeListener = (view, inFocus) -> {
                    if (inFocus) {
                        ((CardView) view).setCardBackgroundColor(getResources().getColor(R.color.colorSecond));
                    } else {
                        ((CardView) view).setCardBackgroundColor(getResources().getColor(R.color.colorFirst));
                    }
                };
                cardTorApps.setOnFocusChangeListener(onFocusChangeListener);
                cardTorApps.setCardBackgroundColor(getResources().getColor(R.color.colorFirst));
            }

            private void bind(int position) {
                ApplicationData app = getItem(position);

                if (position == 0 && cardTorAppFragment != null) {
                    lLayoutTorApps.setPaddingRelative(0, cardTorAppFragment.getHeight() + 10, 0, 0);
                } else {
                    lLayoutTorApps.setPaddingRelative(0, 0, 0, 0);
                }

                tvTorAppName.setText(app.toString());
                if (app.getSystem() && activity != null) {
                    tvTorAppName.setTextColor(ContextCompat.getColor(activity, R.color.textModuleStatusColorAlert));
                } else {
                    tvTorAppName.setTextColor(tvTorAppNameColors);
                }
                imgTorApp.setImageDrawable(app.getIcon());
                String pack = String.format("[%s] %s", app.getUid(), app.getPack());
                tvTorAppPackage.setText(pack);
                swTorApp.setChecked(app.getActive());
            }

        }
    }


    private void getDeviceApps(final Context context, final Set<String> unlockAppsArrListSaved) {

        pbTorApp.setIndeterminate(true);
        pbTorApp.setVisibility(View.VISIBLE);

        futureTask = new FutureTask<>(() -> {

            try {
                InstalledApplications installedApplications = new InstalledApplications(context, unlockAppsArrListSaved);
                installedApplications.setOnAppAddListener(UnlockTorAppsFragment.this);
                List<ApplicationData> installedApps = installedApplications.getInstalledApps(false);

                appsUnlock = new CopyOnWriteArrayList<>(installedApps);

                if (handler != null && pbTorApp != null) {
                    handler.post(() -> {
                        pbTorApp.setIndeterminate(false);
                        pbTorApp.setVisibility(View.GONE);
                        mAdapter.notifyDataSetChanged();
                    });
                }
            } catch (Exception e) {
                Log.e(LOG_TAG, "UnlockTorAppsFragment getDeviceApps exception " + e.getMessage()
                        + "\n" + e.getCause() + "\n" + Arrays.toString(e.getStackTrace()));
            }


            System.gc();

            return null;
        });

        CachedExecutor.INSTANCE.getExecutorService().submit(futureTask);

    }

}
