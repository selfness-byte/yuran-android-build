package com.gongyo.yuranphoto;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.view.*;
import android.widget.*;

/** Shared, static ivory and gold chrome; camera and photo content keep their original colors. */
final class PhotoUi {
 static final int BG=0xfffbf8f1,SURFACE=0xfffffefa,INK=0xff18283f,MUTED=0xff596473,LINE=0xffe6dac2,GOLD=0xffc9a85b;
 private PhotoUi(){}
 static int dp(View view,int value){return Math.round(value*view.getResources().getDisplayMetrics().density);}
 private static GradientDrawable shape(View view,int color,int stroke,int radius){
  GradientDrawable drawable=new GradientDrawable();drawable.setColor(color);drawable.setCornerRadius(dp(view,radius));
  if(stroke!=0)drawable.setStroke(dp(view,1),stroke);return drawable;
 }
 static void card(View view){
  view.setBackground(shape(view,SURFACE,LINE,22));view.setElevation(dp(view,2));
 }
 static void title(TextView view){
  view.setTextColor(INK);view.setTypeface(Typeface.DEFAULT,Typeface.BOLD);view.setLetterSpacing(.02f);
 }
 private static void styleButton(Button button){
  button.setAllCaps(false);button.setTextSize(17);button.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
  button.setMinHeight(dp(button,56));button.setMinimumHeight(dp(button,56));
  button.setPadding(dp(button,18),dp(button,14),dp(button,18),dp(button,14));
  button.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},new int[]{MUTED,INK}));
  GradientDrawable gold=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{0xfff4e3b5,0xffdcc17f});
  gold.setCornerRadius(dp(button,18));gold.setStroke(dp(button,1),GOLD);
  StateListDrawable fill=new StateListDrawable();
  fill.addState(new int[]{-android.R.attr.state_enabled},shape(button,0xffe8e4db,LINE,18));
  fill.addState(new int[]{android.R.attr.state_focused},shape(button,0xffecd598,INK,18));
  fill.addState(new int[]{},gold);
  button.setBackgroundTintList(null);
  button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x2418283f),fill,shape(button,Color.WHITE,0,18)));
 }
 private static void colors(View root){
  if(root instanceof Button)styleButton((Button)root);
  else if(root instanceof EditText){EditText input=(EditText)root;input.setTextColor(INK);input.setHintTextColor(MUTED);input.setBackgroundTintList(ColorStateList.valueOf(0xff795719));input.setMinHeight(dp(root,48));}
  else if(root instanceof TextView)((TextView)root).setTextColor(INK);
  if(root instanceof ViewGroup){ViewGroup group=(ViewGroup)root;for(int i=0;i<group.getChildCount();i++)colors(group.getChildAt(i));}
 }
 static void insets(View root,int padding){
  root.setBackgroundColor(BG);root.post(()->colors(root));
  root.setOnApplyWindowInsetsListener((view,insets)->{
   int left,top,right,bottom;
   if(Build.VERSION.SDK_INT>=30){android.graphics.Insets x=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout()|WindowInsets.Type.ime());left=x.left;top=x.top;right=x.right;bottom=x.bottom;}
   else {left=insets.getSystemWindowInsetLeft();top=insets.getSystemWindowInsetTop();right=insets.getSystemWindowInsetRight();bottom=insets.getSystemWindowInsetBottom();}
   view.setPadding(padding+left,padding+top,padding+right,padding+bottom);return insets;
  });root.requestApplyInsets();
 }
 static void buttons(View root){
  if(root instanceof Button){
   Button button=(Button)root;styleButton(button);
   if(button.getParent() instanceof LinearLayout){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(root,8);button.setLayoutParams(lp);}
  }
  if(root instanceof ViewGroup){ViewGroup group=(ViewGroup)root;for(int i=0;i<group.getChildCount();i++)buttons(group.getChildAt(i));}
 }
 static Button button(LinearLayout parent,String text,View.OnClickListener listener){
  Button button=new Button(parent.getContext());button.setText(text);styleButton(button);button.setOnClickListener(listener);
  LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(parent,8);parent.addView(button,lp);return button;
 }
}
