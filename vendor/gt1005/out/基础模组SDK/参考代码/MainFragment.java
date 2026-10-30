package com.geesatcom.ui.fragment;


import android.os.Bundle;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;


import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;

import com.geesatcom.BaseFragment;
import com.geesatcom.dialog.ProgressDialog;
import com.geesatcom.manager.BleManager;
import com.geesatcom.protocollibrary.Parse.entity.EntityDII;
import com.geesatcom.protocollibrary.Parse.entity.EntityICI;
import com.geesatcom.protocollibrary.Parse.entity.EntityMCI;
import com.geesatcom.protocollibrary.Parse.entity.EntitySFI;
import com.geesatcom.protocollibrary.bean.SubjectTaskBean;
import com.geesatcom.protocollibrary.controller.DispatchManager;
import com.geesatcom.protocollibrary.controller.ModuleDataManager;
import com.geesatcom.protocollibrary.utils.TaskState;
import com.geesatcom.protocollibrary.utils.UsualUtils;
import com.geesatcom.test.CommunicationManager;
import com.geesatcom.toolkit.ToastUtils;
import com.geesatcom.ui.viewmodel.MainViewModel;
import com.geesatcom.ui.viewmodel.MsgViewModel;
import com.geesatcom.R;
import com.geesatcom.databinding.FragmentMainBinding;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import io.reactivex.rxjava3.disposables.CompositeDisposable;

public class MainFragment extends BaseFragment {

    public static final String TAG = "MainFragment";
    private FragmentMainBinding binding;

    private MainViewModel mainViewModel;

    private ProgressDialog dialog;

    private final CompositeDisposable disposable = new CompositeDisposable();

    private final DispatchManager dispatchManager = CommunicationManager.getInstance().dispatchManager;

    private final ModuleDataManager moduleDataManager = CommunicationManager.getInstance().moduleDataManager;

    @Override
    protected View initView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        binding =  FragmentMainBinding.inflate(inflater, container, false);
        mainViewModel = new ViewModelProvider(requireActivity()).get(MainViewModel.class);
        dialog = new ProgressDialog(requireContext());

        return binding.getRoot();
    }

    @Override
    protected void bindLiveData() {
        mainViewModel.getBleState().observe(getViewLifecycleOwner(), bleState -> {
            if (bleState == BleManager.BleState.LINKED) {
                dispatchManager.start();
            }else {
                dispatchManager.stop();
            }
        });

        disposable.add(moduleDataManager.setSelectedSatelliteListening(stringFloatPair ->
                binding.getRoot().post(() -> binding.pdi.setText("PDI: " + stringFloatPair.first + ":" + stringFloatPair.second)))
        );

        disposable.add(moduleDataManager.setEarthStationListening(aBoolean ->
                binding.getRoot().post(() -> binding.spi.setText("SPI: " + aBoolean)))
        );


        disposable.add(moduleDataManager.setICIListening(entityICI ->
                binding.getRoot().post(() -> binding.ici.setText("ICI: " + entityICI.getId())))
        );

        disposable.add(moduleDataManager.setDIIListening(entityDII ->
                binding.getRoot().post(() -> binding.dii.setText("DII: " + entityDII.getModule_SoftwareVersion())))
        );

        disposable.add(moduleDataManager.setSFIMessageListening(entitySFI -> binding.getRoot().post(() -> {
            String succ = entitySFI.getState() == 2 ? "发送成功" : "发送失败";
            binding.sfi.setText("SFI: " + succ + ":" + entitySFI.getId());
        })));

        disposable.add(moduleDataManager.setMCIMessageListening(entityMCI -> binding.getRoot().post(() -> {
            byte[] b  = UsualUtils.hexToByteArray(entityMCI.getHexStr());
            String msg = new String(b, StandardCharsets.UTF_8);
            binding.mci.setText("MCI: " + entityMCI.getCardId() + ":" + msg);
        })));

    }
    @Override
    protected void initData(@NonNull View view, @Nullable Bundle savedInstanceState) {
        binding.link.setOnClickListener(v -> getChildFragmentTransaction()
                .replace(R.id.fragment_container_2, new BleFragment(), BleFragment.class.getCanonicalName())
                .addToBackStack(null)
                .commit());

        binding.sendMcq.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                byte[] bytes = binding.text.getText().toString().getBytes(StandardCharsets.UTF_8);
                sendMCQ(bytes);
            }
        });

        binding.sendSfq.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                byte[] bytes = binding.text.getText().toString().getBytes(StandardCharsets.UTF_8);
                sendSFQ(bytes);
            }
        });

        binding.querySfq.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                querySFQ();
            }
        });

        binding.queryOffline.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                queryOffline();
            }
        });
    }

    private void sendMCQ(byte[] bytes) {
        dialog.show();
        moduleDataManager.sendMsgMCQ("0", UsualUtils.byteArrayToHex(bytes), subjectTaskBean -> binding.getRoot().post(new Runnable() {
            @Override
            public void run() {
                if (subjectTaskBean.state == TaskState.COMPLETE) {
                    Toast.makeText(MainFragment.this.requireContext(), "发送成功", Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                } else if (subjectTaskBean.state == TaskState.FAIL) {
                    Toast.makeText(MainFragment.this.requireContext(), "发送失败: " + subjectTaskBean.bundle, Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                }
            }
        }));
    }

    private void sendSFQ(byte[] bytes) {
        dialog.show();
        moduleDataManager.sendMsgSFQ("0", UsualUtils.byteArrayToHex(bytes), subjectTaskBean -> binding.getRoot().post(new Runnable() {
            @Override
            public void run() {
                if (subjectTaskBean.state == TaskState.COMPLETE) {
                    Toast.makeText(MainFragment.this.requireContext(), "存储成功: " + subjectTaskBean.bundle, Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                } else if (subjectTaskBean.state == TaskState.FAIL) {
                    Toast.makeText(MainFragment.this.requireContext(), "存储失败: " + subjectTaskBean.bundle, Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                }
            }
        }));
    }


    private void querySFQ() {
        dialog.show();
        moduleDataManager.queryMsgSFQ(integer -> binding.getRoot().post(new Runnable() {
            @Override
            public void run() {
                binding.querySfq.setText("SFQ: " + integer);
                dialog.dismiss();
            }
        }));
    }


    private void queryOffline() {
        dialog.show();
        moduleDataManager.queryOfflineMsg(subjectTaskBean -> binding.getRoot().post(new Runnable() {
            @Override
            public void run() {
                if (subjectTaskBean.state == TaskState.COMPLETE) {
                    Toast.makeText(MainFragment.this.requireContext(), "查询完成", Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                } else if (subjectTaskBean.state == TaskState.FAIL) {
                    Toast.makeText(MainFragment.this.requireContext(), "查询失败: " + subjectTaskBean.bundle, Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                }else {
                    int size = subjectTaskBean.bundle instanceof Integer? (int)subjectTaskBean.bundle : 0;
                    binding.queryOffline.setText("Remaining: " + size);
                }
            }
        }));
    }


    @Override
    public void onResume() {
        super.onResume();
    }

    protected <T extends BaseFragment> T getFragment(Class<T> tClass) {
        Fragment fragmentByTag = getChildFragmentManager().findFragmentByTag(tClass.getCanonicalName());
        return tClass.cast(fragmentByTag);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        disposable.clear();
    }

    public void show(BaseFragment fragment) {
        getChildFragmentTransaction()
                .add(R.id.fragment_container_2, fragment, fragment.getClass().getCanonicalName())
                .addToBackStack(null)
                .commit();
    }
}
