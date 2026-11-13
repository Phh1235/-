package com.phh.procure.bootstrap;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.phh.procure.entity.SysUser;
import com.phh.procure.mapper.SysUserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 演示账号初始化：demo / demo123（密码 BCrypt 存储，不写死在 SQL 里）。
 */
@Component
public class DemoDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataInitializer.class);

    private final SysUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    public DemoDataInitializer(SysUserMapper userMapper, PasswordEncoder passwordEncoder) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        Long exists = userMapper.selectCount(Wrappers.<SysUser>lambdaQuery().eq(SysUser::getUsername, "demo"));
        if (exists != null && exists > 0) {
            return;
        }
        SysUser user = new SysUser();
        user.setUsername("demo");
        user.setPassword(passwordEncoder.encode("demo123"));
        user.setDisplayName("演示账号");
        user.setRole("ADMIN");
        user.setEnabled(true);
        userMapper.insert(user);
        log.info("已创建演示账号 demo / demo123");
    }
}
