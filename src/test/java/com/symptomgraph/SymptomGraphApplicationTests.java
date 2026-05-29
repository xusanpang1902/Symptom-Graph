package com.symptomgraph;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "app.oss.endpoint=https://oss-cn-hangzhou.aliyuncs.com",
        "app.oss.bucket=test-bucket",
        "app.oss.access-key-id=test-access-key-id",
        "app.oss.access-key-secret=test-access-key-secret"
})
class SymptomGraphApplicationTests {

    @Test
    void contextLoads() {
    }
}
