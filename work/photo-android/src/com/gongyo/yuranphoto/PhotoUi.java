package com.gongyo.yuranphoto;
import android.os.Build;
import android.view.*;
import android.widget.*;
/** Keep camera actions above system navigation and large enough to tap. */
final class PhotoUi {
 private PhotoUi(){}
 static int dp(View view,int value){return Math.round(value*view.getResources().getDisplayMetrics().density);}
 static void insets(View root,int padding){
  root.setOnApplyWindowInsetsListener((view,insets)->{
   int left,top,right,bottom;
   if(Build.VERSION.SDK_INT>=30){android.graphics.Insets x=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout()|WindowInsets.Type.ime());left=x.left;top=x.top;right=x.right;bottom=x.bottom;}
   else {left=insets.getSystemWindowInsetLeft();top=insets.getSystemWindowInsetTop();right=insets.getSystemWindowInsetRight();bottom=insets.getSystemWindowInsetBottom();}
   view.setPadding(padding+left,padding+top,padding+right,padding+bottom);return insets;
  });root.requestApplyInsets();
 }
 static void buttons(View root){
  if(root instanceof Button){Button b=(Button)root;b.setMinHeight(dp(root,56));b.setTextSize(17);if(b.getParent() instanceof LinearLayout)b.setLayoutParams(new LinearLayout.LayoutParams(-1,-2));}
  if(root instanceof ViewGroup){ViewGroup group=(ViewGroup)root;for(int i=0;i<group.getChildCount();i++)buttons(group.getChildAt(i));}
 }
 static Button button(LinearLayout parent,String text,View.OnClickListener listener){
  Button button=new Button(parent.getContext());button.setText(text);button.setMinHeight(dp(parent,56));button.setTextSize(17);button.setOnClickListener(listener);parent.addView(button,new LinearLayout.LayoutParams(-1,-2));return button;
 }
}
