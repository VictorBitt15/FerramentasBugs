package supplychain.activiti.listener;

import java.io.Serializable;
import java.util.Date;
import java.util.HashMap;
import java.util.List;

import org.activiti.engine.RuntimeService;
import org.activiti.engine.delegate.DelegateExecution;
import org.activiti.engine.delegate.ExecutionListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.zbq.GlobalVariables;

import supplychain.entity.VPort;

@Service("anchStartListener")
public class AnchorStartListener implements ExecutionListener, Serializable {

	/**
	 * 
	 */
	private static final long serialVersionUID = 4149621500319226872L;
	@Autowired
	private transient RuntimeService runtimeService;

	@Autowired
	private transient GlobalVariables globalVariables;
	@Override
	public void notify(DelegateExecution exec) {
		String preports = "PrePort";
		String targLocLists = "TargLocList";
		String pid = exec.getProcessInstanceId();
		//runtimeService.setVariable(pid , "State" , "voyaging");
		HashMap<String, Object> vars = (HashMap<String, Object>) runtimeService
				.getVariables(pid);
		VPort preport = (VPort) vars.get(preports);
		System.out.println(preport.toString());
		@SuppressWarnings("unchecked")
		List<VPort> targLocList = (List<VPort>) vars.get(targLocLists);
		for (int i = 0; i < targLocList.size(); i++) {
			VPort now = targLocList.get(i);
			if (now.getPname().equals(preport.getPname())) {
				now.setState("InAD");
				targLocList.set(i, now);
				System.out.println(preport.getPname()+" 到达，更新TargLocList完毕!");
			}
		}
		runtimeService.setVariable(pid ,targLocLists, targLocList);
		runtimeService.setVariable(pid, preports, preport);
		globalVariables.createOrUpdateVariableByNameAndValue(pid, targLocLists, targLocList);
		globalVariables.createOrUpdateVariableByNameAndValue(pid, preports, preport);
		System.out.println("进入anchoring : " + new Date());
	}


}
