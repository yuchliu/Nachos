package nachos.userprog;

import java.util.ArrayList;
import java.util.LinkedList;

import nachos.machine.*;
import nachos.threads.*;
import nachos.userprog.*;

/**
 * A kernel that can support multiple user processes.
 */
public class UserKernel extends ThreadedKernel {

	// LinkedList to store available pages.
	static LinkedList<Integer> emptyList;
	// ArrayList to store page status
	static ArrayList<Boolean> pStatus;

	/**
	 * Allocate a new user kernel.
	 */
	public UserKernel() {
		super();
	}

	/**
	 * Initialize this kernel. Creates a synchronized console and sets the
	 * processor's exception handler.
	 */
	public void initialize(String[] args) {
		super.initialize(args);

		console = new SynchConsole(Machine.console());

		Machine.processor().setExceptionHandler(new Runnable() {
			public void run() {
				exceptionHandler();
			}
		});

		// Initialize the available page linked list and page status
		// Get number of available physical pages by Machine.processor().getNumPhysPages()
		emptyList = new LinkedList<Integer>();
		pStatus = new ArrayList<Boolean>();
		// FIXME: considering put interrupt disable here
		for(int i=0; i<Machine.processor().getNumPhysPages();i++){
			emptyList.add(i);
			pStatus.add(false);
		}
	}

	// Allocate pages: always pop out from the front of the linked list. In this way, it can avoid the fragmentation.
	public static int allocPage(){
		Machine.interrupt().disable();
		if(emptyList.size() < 1){
			Machine.interrupt().enable();
			return -1;
		}else{
			int currentPage = emptyList.pop();
			Lib.assertTrue(pStatus.get(currentPage) == false);
			pStatus.set(currentPage, true);
			Machine.interrupt().enable();
			return currentPage;
		}
	}

	// Free the pages. Add it back to the front of the available page linked list.
	// So if a process frees the page, leaves a hole. The new coming process should be able to utilize it, instead of skip it and find contiguous pages.
	public static void freePage(int currentPage){
		Machine.interrupt().disable();
		Lib.assertTrue(pStatus.get(currentPage) == true);
		pStatus.set(currentPage, false);
		emptyList.push(currentPage);
		Machine.interrupt().enable();
	}

	/**
	 * Test the console device.
	 */
	public void selfTest() {
		super.selfTest();

		System.out.println("Testing allocate");
		ArrayList<Integer> testAlloc = new ArrayList<Integer>();
		int allocPage;
		while ((allocPage = allocPage()) >= 0) {
			testAlloc.add(allocPage);
		}
		int allocated = testAlloc.size();
		System.out.println(allocated + " pages allocated");

		System.out.println("Testing deallocate odd (fragmentation check)");
		int deallocated = 0;
		for (int i=testAlloc.size()-1; i>=0; i-=2) {
			freePage( testAlloc.remove(i) );
			deallocated ++;
		}

		System.out.println("Reallocating odd pages");
		for (int i=0; i<deallocated; i++) {
			allocPage = allocPage();
			Lib.assertTrue(allocPage >= 0);
			testAlloc.add(allocPage);
		}

		System.out.println("Deallocating all pages");
		deallocated = 0;
		for (Integer deallocPage:testAlloc) {
			freePage(deallocPage);
			deallocated++;
		}
		testAlloc.clear();
		Lib.assertTrue(deallocated == allocated);

		System.out.println("Reallocating all pages");
		for (int i=0; i<allocated; i++) {
			testAlloc.add(allocPage());
		}

		System.out.println("Deallocating all pages");
		for (Integer deallocPage:testAlloc) {
			freePage(deallocPage);
		}

		System.out.println("Page allocation OK.");

		System.out.println("Testing the console device. Typed characters");
		System.out.println("will be echoed until q is typed.");

		char c;

		do {
			c = (char) console.readByte(true);
			console.writeByte(c);
		} while (c != 'q');

		System.out.println("");
	}

	/**
	 * Returns the current process.
	 *
	 * @return the current process, or <tt>null</tt> if no process is current.
	 */
	public static UserProcess currentProcess() {
		if (!(KThread.currentThread() instanceof UThread))
			return null;

		return ((UThread) KThread.currentThread()).process;
	}

	/**
	 * The exception handler. This handler is called by the processor whenever a
	 * user instruction causes a processor exception.
	 *
	 * <p>
	 * When the exception handler is invoked, interrupts are enabled, and the
	 * processor's cause register contains an integer identifying the cause of
	 * the exception (see the <tt>exceptionZZZ</tt> constants in the
	 * <tt>Processor</tt> class). If the exception involves a bad virtual
	 * address (e.g. page fault, TLB miss, read-only, bus error, or address
	 * error), the processor's BadVAddr register identifies the virtual address
	 * that caused the exception.
	 */
	public void exceptionHandler() {
		Lib.assertTrue(KThread.currentThread() instanceof UThread);

		UserProcess process = ((UThread) KThread.currentThread()).process;
		int cause = Machine.processor().readRegister(Processor.regCause);
		process.handleException(cause);
	}

	/**
	 * Start running user programs, by creating a process and running a shell
	 * program in it. The name of the shell program it must run is returned by
	 * <tt>Machine.getShellProgramName()</tt>.
	 *
	 * @see nachos.machine.Machine#getShellProgramName
	 */
	public void run() {
		super.run();

		UserProcess process = UserProcess.newUserProcess();

		String shellProgram = Machine.getShellProgramName();
		Lib.assertTrue(process.execute(shellProgram, new String[] {}));

		KThread.currentThread().finish();
	}

	/**
	 * Terminate this kernel. Never returns.
	 */
	public void terminate() {
		super.terminate();
	}

	/** Globally accessible reference to the synchronized console. */
	public static SynchConsole console;

	// dummy variables to make javac smarter
	private static Coff dummy1 = null;
}