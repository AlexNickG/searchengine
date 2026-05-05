package searchengine.controllers;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import searchengine.Repositories.UserRepository;
import searchengine.dto.admin.ChangePasswordRequest;
import searchengine.dto.admin.CreateUserRequest;
import searchengine.dto.admin.UserResponse;
import searchengine.model.AppUser;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class UserController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @GetMapping
    public List<UserResponse> listUsers() {
        return userRepository.findAll().stream()
                .map(u -> new UserResponse(u.getId(), u.getUsername(), u.getRole(), u.isEnabled()))
                .toList();
    }

    @PostMapping
    public ResponseEntity<?> createUser(@RequestBody CreateUserRequest req) {
        if (req.getUsername() == null || req.getUsername().isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "Username is required"));
        if (req.getPassword() == null || req.getPassword().length() < 4)
            return ResponseEntity.badRequest().body(Map.of("error", "Password must be at least 4 characters"));
        if (userRepository.findByUsername(req.getUsername()).isPresent())
            return ResponseEntity.badRequest().body(Map.of("error", "Username already exists"));

        String role = "ROLE_ADMIN".equals(req.getRole()) ? "ROLE_ADMIN" : "ROLE_USER";

        AppUser user = new AppUser();
        user.setUsername(req.getUsername().trim());
        user.setPassword(passwordEncoder.encode(req.getPassword()));
        user.setRole(role);
        user.setEnabled(true);
        AppUser saved = userRepository.save(user);
        return ResponseEntity.ok(new UserResponse(saved.getId(), saved.getUsername(), saved.getRole(), saved.isEnabled()));
    }

    @PatchMapping("/{id}/password")
    public ResponseEntity<?> changePassword(@PathVariable Long id, @RequestBody ChangePasswordRequest req) {
        if (req.getNewPassword() == null || req.getNewPassword().length() < 4)
            return ResponseEntity.badRequest().body(Map.of("error", "Password must be at least 4 characters"));

        return userRepository.findById(id)
                .map(user -> {
                    user.setPassword(passwordEncoder.encode(req.getNewPassword()));
                    userRepository.save(user);
                    return ResponseEntity.ok(Map.of("result", true));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteUser(@PathVariable Long id, Authentication auth) {
        return userRepository.findById(id)
                .map(user -> {
                    if (user.getUsername().equals(auth.getName()))
                        return ResponseEntity.badRequest().body(Map.<String, Object>of("error", "Cannot delete your own account"));
                    userRepository.delete(user);
                    return ResponseEntity.ok(Map.<String, Object>of("result", true));
                })
                .orElse(ResponseEntity.notFound().build());
    }
}
