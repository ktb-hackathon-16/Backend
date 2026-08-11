package com.ktb.chatapp.security;

import com.ktb.chatapp.model.User;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

@RequiredArgsConstructor
public final class AuthenticatedUser implements UserDetails {

    private final User user;

    public User getUser() {
        return user;
    }

    @Override
    public List<? extends GrantedAuthority> getAuthorities() {
        return List.of();
    }

    @Override
    public String getPassword() {
        return user.getPassword();
    }

    @Override
    public String getUsername() {
        return user.getEmail();
    }
}
