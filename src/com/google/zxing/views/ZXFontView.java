package com.google.zxing.views;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.widget.TextView;

import com.google.zxing.client.android.R;

public class ZXFontView extends TextView {
    private String fontName;

    public ZXFontView(Context context) {
        super(context);
        initView(null);
    }

    public ZXFontView(Context context, AttributeSet attrs) {
        super(context, attrs);
        initView(attrs);
    }

    public ZXFontView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        initView(attrs);
    }

    private void initView(AttributeSet attrs) {
        TypedArray array = getContext().obtainStyledAttributes(attrs, R.styleable.FontWidget);

        fontName = array.getString(R.styleable.FontWidget_fontName);
        if(fontName != null){
            setTypeface(Typeface.createFromAsset(getContext().getAssets(), "fonts/" + fontName));
        }
        array.recycle();
    }
}
