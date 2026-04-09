package com.charmnight.linkgraph.fixtures.mybatis;

public interface UserMapper {
    String selectUser(String id);
}

class UserQueryService {
    private final UserMapper userMapper;

    UserQueryService(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    public String loadUser(String id) {
        return userMapper.selectUser(id);
    }
}
