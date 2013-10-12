// Copyright (C) 2008 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.client.account;

import com.google.gerrit.client.rpc.GerritCallback;
import com.google.gerrit.client.rpc.ScreenLoadCallback;
import com.google.gerrit.reviewdb.client.AccountCvsCredentials;
import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.event.dom.client.ClickHandler;
import com.google.gwt.user.client.ui.Button;
import com.google.gwt.user.client.ui.Grid;
import com.google.gwt.user.client.ui.TextBox;
import com.google.gwtexpui.globalkey.client.NpTextArea;

public class MyCvsScreen extends SettingsScreen {
  private Button saveChanges;
  private Grid grid;
  private TextBox cvsLoginTxt;
  private NpTextArea cvsPrivateKeyTxt;
  private String cvsPrivateKey;

  @Override
  protected void onInitUI() {
    //Logger logger = Logger.getLogger(getClass().getName());
    //logger.log(Level.SEVERE, "wtf???");

    super.onInitUI();
    createWidgets();

    grid = new Grid(2, 2);
    grid.setText(0, 0, Util.C.cvsLogin());
    grid.setWidget(0, 1, cvsLoginTxt);
    grid.setText(1, 0, Util.C.cvsPrivateKey());
    grid.setWidget(1, 1, cvsPrivateKeyTxt);

    add(grid);
    add(saveChanges);
  }

  protected void createWidgets() {
    saveChanges = new Button(Util.C.buttonSaveChanges());
    saveChanges.addClickHandler(new ClickHandler() {
      @Override
      public void onClick(ClickEvent event) {
        doSaveCvsAccountInfo();
      }
    });

    cvsLoginTxt = new TextBox();
    cvsPrivateKeyTxt = new NpTextArea();
    cvsPrivateKeyTxt.setVisibleLines(28);
    cvsPrivateKeyTxt.setCharacterWidth(66);
  }

  private void doSaveCvsAccountInfo() {
    final String user = cvsLoginTxt.getText();
    String key = cvsPrivateKeyTxt.getText();
    saveChanges.setEnabled(false);
    if (key.equals("saved"))
        key = cvsPrivateKey;
    Util.ACCOUNT_SEC.changeCvsCredentials(user, key, new GerritCallback<AccountCvsCredentials>() {
      public void onSuccess(final AccountCvsCredentials result) {
        saveChanges.setEnabled(true);
        cvsLoginTxt.setText(result.getCvsUser());
        cvsPrivateKeyTxt.setText("saved");
      }

      @Override
      public void onFailure(final Throwable caught) {
        saveChanges.setEnabled(true);
        //cvsLoginTxt.setText("failed");
        //cvsPrivateKeyTxt.setText("failed");
        super.onFailure(caught);
      }
    });
  }

  @Override
  protected void onLoad() {
    super.onLoad();
    Util.ACCOUNT_SEC.myCvsCredentials(new ScreenLoadCallback<AccountCvsCredentials>(this) {
      public void preDisplay(final AccountCvsCredentials result) {
        cvsLoginTxt.setText(result.getCvsUser());
        if (result.getSshPrivateKey().contains("PRIVATE KEY")) {
            cvsPrivateKeyTxt.setText("saved");
            cvsPrivateKey = result.getSshPrivateKey();
        } else {
            cvsPrivateKeyTxt.setText(result.getSshPrivateKey());
        }
      }
    });
    display();
    populate();
  }

  protected void populate() {
  }
}
