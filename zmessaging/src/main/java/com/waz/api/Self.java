/*
 * Wire
 * Copyright (C) 2016 Wire Swiss GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.waz.api;

public interface Self extends UiObservable {

    /**
     * @return current user - will be null if user is not logged in
     */
    User getUser();

    boolean isLoggedIn();

    AccentColor getAccent();

    String getName();

    String getEmail();

    String getPhone();

    ImageAsset getPicture();

    /**
     * Returns current otr client.
     */
    UiSignal<OtrClient> getOtrClient();

    CoreList<OtrClient> getOtherOtrClients();

    /**
     * Returns recently added and not yet verified otr clients.
     * Used to notify user about new devices added to his account.
     */
    CoreList<OtrClient> getIncomingOtrClients();

    boolean accountActivated();

    /**
     * @deprecated - use accountActivated instead
     */
    @Deprecated
    boolean isEmailVerified();
    boolean isPhoneVerified();

    void resendVerificationEmail(String email);

    boolean isUpToDate();

    void setName(String name);

    /**
     * Sets/Updates the current user's email address. This will send a verification email to the given email address. Until then, the email address
     * will remain unverified.
     */
    void setEmail(String email, CredentialsUpdateListener listener);

    void clearEmail(CredentialsUpdateListener listener);

    /**
     * Sets/Updates the current user's phone number. This will send a phone number verification code as a text to the given phone number. Until then, the phone number
     * will remain unverified.
     */
    void setPhone(String phone, CredentialsUpdateListener listener);

    void clearPhone(CredentialsUpdateListener listener);

    /**
     * Updates the current user's existing password.
     */
    void updatePassword(String newPassword, String currentPassword, CredentialsUpdateListener listener);

    /**
     * Sets a new password for a user that currently doesn't have one.
     */
    void setPassword(String password, CredentialsUpdateListener listener);

    /**
     * Requests account deletion. Doesn't actually delete the account. Instead, this will only send a verification request to the user by email or sms.
     */
    void deleteAccount();

    void setAccent(AccentColor color);
    void setPicture(ImageAsset image);

    void clearPicture();

    String getUsername();

    void setUsername(String username, CredentialsUpdateListener listener);

    boolean hasSetUsername();

    boolean isTeamAccount();

}
