package cn.edu.nju.p.controller.personnel;

import cn.edu.nju.p.baseresult.BaseResult;
import cn.edu.nju.p.service.personnel.PersonnalInfoService;
import com.alibaba.fastjson.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * Created by cyz on 2017/6/6.
 */
@RestController
@RequestMapping("/personnel")
public class PersonnalInfoController {

    @Autowired
    private PersonnalInfoService personnalInfoService;

    @GetMapping("/{phoneNumber}")
    public BaseResult getClientInfo(@PathVariable String phoneNumber) {
        return new BaseResult<>(0,personnalInfoService.getClientInfo(phoneNumber));
    }

    @PostMapping("/update")
    public BaseResult updateClientInfo(@RequestBody JSONObject object) {
        String phone_number = object.getString("phone_number");
        String user_name= object.getString("user_name");
        String sex = object.getString("sex");
        String email = object.getString("email");
        String unit = object.getString("unit");
        String place = object.getString("place");
        personnalInfoService.updateClient(phone_number, user_name, sex, email, unit, place);
        return new BaseResult(0, "update client info successfully!");
    }


    @PostMapping("/updatePass")
    public BaseResult updatePass(@RequestBody JSONObject jsonObject){
        String phone_number = jsonObject.getString("phone_number");
        String password = jsonObject.getString("password");
        personnalInfoService.updatePass(phone_number,password);
        return new BaseResult(0,"update password successfully!");
    }
}
