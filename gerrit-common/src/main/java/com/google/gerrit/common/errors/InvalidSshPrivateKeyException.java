// Copyright (C) 2009 The Android Open Source Project
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

package com.google.gerrit.common.errors;

/** Error indicating the SSH key string is invalid as supplied. */
public class InvalidSshPrivateKeyException extends Exception {
  private static final long serialVersionUID = 1L;

  public static final String MESSAGE = "Invalid SSH Private Key (note that it should be private not public key)";

  public InvalidSshPrivateKeyException() {
    super(MESSAGE);
  }
}
