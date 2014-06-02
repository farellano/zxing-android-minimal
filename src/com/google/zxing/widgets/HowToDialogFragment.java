package com.google.zxing.widgets;


import android.app.Dialog;
import android.app.DialogFragment;
import android.app.FragmentManager;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.google.zxing.client.android.R;


/**
 * Created by Hugo on 1/30/14.
 */
public class HowToDialogFragment extends DialogFragment{

    private int tutorialIndex = 1;

    private int imageResource;
    private boolean simpleDialog;
    private String informationMessage;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setStyle(DialogFragment.STYLE_NORMAL,R.style.CustomDialog);

        Bundle bundle;

        if (savedInstanceState == null) {
            bundle = getArguments();
        } else {
            bundle = savedInstanceState;
        }

    }

    @Override
    public Dialog onCreateDialog(final Bundle savedInstanceState) {
        Dialog dialog = null;

        dialog = new Dialog(getActivity());
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);


        final RelativeLayout root;

        LayoutInflater inflater = LayoutInflater.from(getActivity());
        RelativeLayout dialogContent = (RelativeLayout) inflater.inflate(R.layout.fragment_how_to,null);

        final ImageView tutorialImage = (ImageView) dialogContent.findViewById(R.id.fragment_how_to_tutorial_iv);
        tutorialImage.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                tutorialIndex+=1;
                switch (tutorialIndex){
                    case 1:
                        tutorialImage.setImageResource(R.drawable.tutorial_screen_1);
                        break;
                    case 2:
                        tutorialImage.setImageResource(R.drawable.tutorial_screen_2);
                        break;
                    case 3:
                        tutorialImage.setImageResource(R.drawable.tutorial_screen_3);
                        break;
                    case 4:
                        dismissDialog();
                        break;
                }

            }
        });

        dialogContent.findViewById(R.id.fragment_how_to_skip_btn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                dismissDialog();
            }
        });


        // creating the fullscreen dialog

        dialog.setContentView(dialogContent);

        dialog.getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);

        return dialog;
    }


    public void showDialog(FragmentManager manager) {
        show(manager, "");
    }

    public void dismissDialog(){
        dismiss();
    }
}
