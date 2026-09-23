package io.github.easyat.boot3;
import io.github.easyat.core.*;
import io.github.easyat.spring.CoordinationService;
import jakarta.servlet.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController @RequestMapping("/_easy-at/v1")
public final class EasyAtCoordinationController {
    private final CoordinationService service;
    public EasyAtCoordinationController(CoordinationService service){this.service=service;}
    private Map<String,String> headers(HttpServletRequest r){Map<String,String> m=new HashMap<String,String>();Enumeration<String> e=r.getHeaderNames();while(e!=null&&e.hasMoreElements()){String n=e.nextElement();m.put(n,r.getHeader(n));}return m;}
    @PostMapping("/branches") public Map<String,Object> register(@RequestBody Map<String,String> body,HttpServletRequest r){return service.register(headers(r),body);}
    @PostMapping("/branches/{branchId}/commit") public Map<String,Object> commit(@PathVariable String branchId,HttpServletRequest r){return service.commit(headers(r),branchId);}
    @PostMapping("/branches/{branchId}/rollback") public Map<String,Object> rollback(@PathVariable String branchId,HttpServletRequest r){return service.rollback(headers(r),branchId);}
}
