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

package com.google.gerrit.reviewdb.client;

import com.google.gwtorm.client.Column;

/** An SSH key approved for use by an {@link Account}. */
public final class AccountCvsCredentials {

  @Column(id = 1, name = Column.NONE)
  protected Account.Id accountId;

  @Column(id = 2, length = Integer.MAX_VALUE)
  protected String sshPrivateKey;

  @Column(id = 3)
  protected String cvsUser;

  protected AccountCvsCredentials() {
  }

  public AccountCvsCredentials(final Account.Id i) {
    accountId = i;
  }

  public AccountCvsCredentials(final Account.Id i, final String key, final String user) {
    accountId = i;
    sshPrivateKey = key;
    cvsUser = user;
  }

  public Account.Id getAccountId() {
    return accountId;
  }

  public String getSshPrivateKey() {
    return sshPrivateKey;
  }

  public String getCvsUser() {
    return cvsUser;
  }

  public void setSshPrivateKey(final String key) {
    sshPrivateKey = key;
  }

  public void setCvsUser(final String user) {
    cvsUser = user;
  }

  /*public String getAlgorithm() {
    final String s = getSshPrivateKey();
    if (s == null || s.length() == 0) {
      return "none";
    }

    final String[] parts = s.split(" ");
    if (parts.length < 1) {
      return "none";
    }
    return parts[0];
  }

  public String getEncodedKey() {
    final String s = getSshPrivateKey();
    if (s == null || s.length() == 0) {
      return null;
    }

    final String[] parts = s.split(" ");
    if (parts.length < 2) {
      return null;
    }
    return parts[1];
  }

  public String getComment() {
    final String s = getSshPrivateKey();
    if (s == null || s.length() == 0) {
      return "";
    }

    final String[] parts = s.split(" ", 3);
    if (parts.length < 3) {
      return "";
    }
    return parts[2];
  }*/
}
