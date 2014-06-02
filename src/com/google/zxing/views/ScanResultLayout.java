package com.google.zxing.views;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.zxing.client.android.R;

/**
 * Created by fernandoarellano on 5/28/14.
 */
public class ScanResultLayout extends LinearLayout {

    private static final int TIME_VISIBLE = 1000;

    public enum SCAN_RESULT{
        VALID,
        INVALID,
        USED,
        FULL
    }

    ImageView imageView;
    ZXFontView textView;

    Runnable autoHideRunnable = new Runnable() {
        @Override
        public void run() {
            ScanResultLayout.this.setVisibility(View.GONE);
        }
    };


    public ScanResultLayout(Context context) {
        super(context);
        this.init(context);
    }

    public ScanResultLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        this.init(context);
    }

    public ScanResultLayout(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        this.init(context);
    }

    private void init(Context context){

    }

    public void setScanResult(SCAN_RESULT result){
        setScanResult(result,null);
    }

    public void setScanResult(SCAN_RESULT result, Integer passCount){
        imageView = (ImageView) getChildAt(0);
        textView = (ZXFontView) getChildAt(1);
        switch (result){
            case VALID:
                setBackgroundColor(getResources().getColor(R.color.color_verified));
                imageView.setImageResource(R.drawable.ico_verified);
                if (passCount != null)
                    textView.setText(String.format("Verified \n%d seats",passCount));
                break;
            case INVALID:
                setBackgroundColor(getResources().getColor(R.color.color_invalid));
                imageView.setImageResource(R.drawable.ico_used);
                textView.setText("Invalid \nPass");
                break;
            case USED:
                setBackgroundColor(getResources().getColor(R.color.color_full));
                imageView.setImageResource(R.drawable.ico_used);
                textView.setText("Pass \nUsed");
                break;
            case FULL:
                setBackgroundColor(getResources().getColor(R.color.color_full));
                imageView.setImageResource(R.drawable.ico_full);
                textView.setText("Event \nFull");
                break;
        }
        this.setVisibility(View.VISIBLE);
        this.postDelayed(autoHideRunnable,TIME_VISIBLE);
    }
}
