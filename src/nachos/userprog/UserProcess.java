package nachos.userprog;

import nachos.machine.*;
import nachos.threads.*;

import java.io.EOFException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;

/**
 * Encapsulates the state of a user process that is not contained in its user
 * thread (or threads). This includes its address translation state, a file
 * table, and information about the program being executed.
 *
 * <p>
 * This class is extended by other classes to support additional functionality
 * (such as additional syscalls).
 *
 * @see nachos.vm.VMProcess
 * @see nachos.network.NetProcess
 */
public class UserProcess {
	/**
	 * Allocate a new process.
	 */
	public UserProcess() {
		int numPhysPages = Machine.processor().getNumPhysPages();
		pageTable = new TranslationEntry[numPhysPages];
		for (int i = 0; i < numPhysPages; i++)
			pageTable[i] = new TranslationEntry(i, i, true, false, false, false);

		/** initialize file availableDescriptors */
		availableDescriptors = new ArrayList<Integer>(Arrays.asList(2,3,4,5,6,7,8,9,10,11,12,13,14,15));

		/** reserved for stdin and stdout(by requirement) */
		openFileMap[0] = UserKernel.console.openForReading();
		openFileMap[1] = UserKernel.console.openForWriting();
		for (int i = 2; i < openFileMap.length; i++)
			openFileMap[i] = null;

		pid = processCount++;
		children = new HashMap<Integer,UserProcess>();
		exitStatus = 1;
	}

	/**
	 * Allocate and return a new process of the correct class. The class name is
	 * specified by the <tt>nachos.conf</tt> key
	 * <tt>Kernel.processClassName</tt>.
	 *
	 * @return a new process of the correct class.
	 */
	public static UserProcess newUserProcess() {
		return (UserProcess) Lib.constructObject(Machine.getProcessClassName());
	}

	/**
	 * Execute the specified program with the specified arguments. Attempts to
	 * load the program, and then forks a thread to run it.
	 *
	 * @param name the name of the file containing the executable.
	 * @param args the arguments to pass to the executable.
	 * @return <tt>true</tt> if the program was successfully executed.
	 */
	public boolean execute(String name, String[] args) {
		if (!load(name, args))
			return false;

		new UThread(this).setName(name).fork();

		return true;
	}

	/**
	 * Save the state of this process in preparation for a context switch.
	 * Called by <tt>UThread.saveState()</tt>.
	 */
	public void saveState() {
	}

	/**
	 * Restore the state of this process after a context switch. Called by
	 * <tt>UThread.restoreState()</tt>.
	 */
	public void restoreState() {
		Machine.processor().setPageTable(pageTable);
	}

	/**
	 * Read a null-terminated string from this process's virtual memory. Read at
	 * most <tt>maxLength + 1</tt> bytes from the specified address, search for
	 * the null terminator, and convert it to a <tt>java.lang.String</tt>,
	 * without including the null terminator. If no null terminator is found,
	 * returns <tt>null</tt>.
	 *
	 * @param vaddr the starting virtual address of the null-terminated string.
	 * @param maxLength the maximum number of characters in the string, not
	 * including the null terminator.
	 * @return the string read, or <tt>null</tt> if no null terminator was
	 * found.
	 */
	public String readVirtualMemoryString(int vaddr, int maxLength) {
		Lib.assertTrue(maxLength >= 0);

		byte[] bytes = new byte[maxLength + 1];

		int bytesRead = readVirtualMemory(vaddr, bytes);

		for (int length = 0; length < bytesRead; length++) {
			if (bytes[length] == 0)
				return new String(bytes, 0, length);
		}

		return null;
	}

	/**
	 * Transfer data from this process's virtual memory to all of the specified
	 * array. Same as <tt>readVirtualMemory(vaddr, data, 0, data.length)</tt>.
	 *
	 * @param vaddr the first byte of virtual memory to read.
	 * @param data the array where the data will be stored.
	 * @return the number of bytes successfully transferred.
	 */
	public int readVirtualMemory(int vaddr, byte[] data) {
		return readVirtualMemory(vaddr, data, 0, data.length);
	}

	/**
	 * Transfer data from this process's virtual memory to the specified array.
	 * This method handles address translation details. This method must
	 * <i>not</i> destroy the current process if an error occurs, but instead
	 * should return the number of bytes successfully copied (or zero if no data
	 * could be copied).
	 *
	 * @param vaddr the first byte of virtual memory to read.
	 * @param data the array where the data will be stored.
	 * @param offset the first byte to write in the array.
	 * @param length the number of bytes to transfer from virtual memory to the
	 * array.
	 * @return the number of bytes successfully transferred.
	 */
	public int readVirtualMemory(int vaddr, byte[] data, int offset, int length) {
		Lib.assertTrue(offset >= 0 && length >= 0
				&& offset + length <= data.length);

		byte[] memory = Machine.processor().getMemory();

		// FIXME: not sure this is necessary or not
		// for now, just assume that virtual addresses equal physical addresses
		if (vaddr < 0 || vaddr >= memory.length)
			return 0;

		// To record the number of bytes successfully copied (or zero if no data could be copied).
		int totalTransf = 0;

		while(length > 0 && offset < data.length){
			int addr = vaddr%1024;
			int virtualP = vaddr/1024;
			if(virtualP >= pageTable.length || virtualP < 0)
				break;
			TranslationEntry pte=pageTable[virtualP];
			if(!pte.valid) break;
			pte.used=true;
			int physicalP=pte.ppn;
			int physicalAddr=physicalP*1024+addr;
			int amount = Math.min(data.length-offset, Math.min(length, 1024-addr));
			System.arraycopy(memory, physicalAddr, data, offset, amount);
			vaddr+=amount;
			offset+=amount;
			length-=amount;
			totalTransf+=amount;
		}

		return totalTransf;
	}

	/**
	 * Transfer all data from the specified array to this process's virtual
	 * memory. Same as <tt>writeVirtualMemory(vaddr, data, 0, data.length)</tt>.
	 *
	 * @param vaddr the first byte of virtual memory to write.
	 * @param data the array containing the data to transfer.
	 * @return the number of bytes successfully transferred.
	 */
	public int writeVirtualMemory(int vaddr, byte[] data) {
		return writeVirtualMemory(vaddr, data, 0, data.length);
	}

	/**
	 * Transfer data from the specified array to this process's virtual memory.
	 * This method handles address translation details. This method must
	 * <i>not</i> destroy the current process if an error occurs, but instead
	 * should return the number of bytes successfully copied (or zero if no data
	 * could be copied).
	 *
	 * @param vaddr the first byte of virtual memory to write.
	 * @param data the array containing the data to transfer.
	 * @param offset the first byte to transfer from the array.
	 * @param length the number of bytes to transfer from the array to virtual
	 * memory.
	 * @return the number of bytes successfully transferred.
	 */
	public int writeVirtualMemory(int vaddr, byte[] data, int offset, int length) {
		Lib.assertTrue(offset >= 0 && length >= 0
				&& offset + length <= data.length);

		byte[] memory = Machine.processor().getMemory();

		// FIXME: not sure this is necessary or not
		// for now, just assume that virtual addresses equal physical addresses
		if (vaddr < 0 || vaddr >= memory.length)
			return 0;

		int totalTransf = 0;

		while(length>0&&offset<data.length){
			int addr=vaddr%1024;
			int virtualP=vaddr/1024;
			if(virtualP>=pageTable.length||virtualP<0)
				break;
			TranslationEntry pte=pageTable[virtualP];
			if(!pte.valid||pte.readOnly) break;
			pte.used=true;
			pte.dirty=true;
			int physicalP=pte.ppn;
			int physicalAddr=physicalP*1024+addr;
			int amount = Math.min(data.length-offset, Math.min(length, 1024-addr));
			System.arraycopy(data, offset, memory, physicalAddr, amount);
			vaddr+=amount;
			offset+=amount;
			length-=amount;
			totalTransf+=amount;
		}

		return totalTransf;
	}

	/**
	 * Load the executable with the specified name into this process, and
	 * prepare to pass it the specified arguments. Opens the executable, reads
	 * its header information, and copies sections and arguments into this
	 * process's virtual memory.
	 *
	 * @param name the name of the file containing the executable.
	 * @param args the arguments to pass to the executable.
	 * @return <tt>true</tt> if the executable was successfully loaded.
	 */
	private boolean load(String name, String[] args) {
		Lib.debug(dbgProcess, "UserProcess.load(\"" + name + "\")");

		OpenFile executable = ThreadedKernel.fileSystem.open(name, false);
		if (executable == null) {
			Lib.debug(dbgProcess, "\topen failed");
			return false;
		}

		try {
			coff = new Coff(executable);
		}
		catch (EOFException e) {
			executable.close();
			Lib.debug(dbgProcess, "\tcoff load failed");
			return false;
		}

		// make sure the sections are contiguous and start at page 0
		numPages = 0;
		for (int s = 0; s < coff.getNumSections(); s++) {
			CoffSection section = coff.getSection(s);
			if (section.getFirstVPN() != numPages) {
				coff.close();
				Lib.debug(dbgProcess, "\tfragmented executable");
				return false;
			}
			numPages += section.getLength();
		}

		// make sure the argv array will fit in one page
		byte[][] argv = new byte[args.length][];
		int argsSize = 0;
		for (int i = 0; i < args.length; i++) {
			argv[i] = args[i].getBytes();
			// 4 bytes for argv[] pointer; then string plus one for null byte
			argsSize += 4 + argv[i].length + 1;
		}
		if (argsSize > pageSize) {
			coff.close();
			Lib.debug(dbgProcess, "\targuments too long");
			return false;
		}

		// program counter initially points at the program entry point
		initialPC = coff.getEntryPoint();

		// next comes the stack; stack pointer initially points to top of it
		numPages += stackPages;
		initialSP = numPages * pageSize;

		// and finally reserve 1 page for arguments
		numPages++;

		if (!loadSections())
			return false;

		// store arguments in last page
		int entryOffset = (numPages - 1) * pageSize;
		int stringOffset = entryOffset + args.length * 4;

		this.argc = args.length;
		this.argv = entryOffset;

		for (int i = 0; i < argv.length; i++) {
			byte[] stringOffsetBytes = Lib.bytesFromInt(stringOffset);
			Lib.assertTrue(writeVirtualMemory(entryOffset, stringOffsetBytes) == 4);
			entryOffset += 4;
			Lib.assertTrue(writeVirtualMemory(stringOffset, argv[i]) == argv[i].length);
			stringOffset += argv[i].length;
			Lib.assertTrue(writeVirtualMemory(stringOffset, new byte[] { 0 }) == 1);
			stringOffset += 1;
		}

		return true;
	}

	/**
	 * Allocates memory for this process, and loads the COFF sections into
	 * memory. If this returns successfully, the process will definitely be run
	 * (this is the last step in process initialization that can fail).
	 *
	 * @return <tt>true</tt> if the sections were successfully loaded.
	 */
	protected boolean loadSections() {
		if (numPages > Machine.processor().getNumPhysPages()) {
			coff.close();
			Lib.debug(dbgProcess, "\tinsufficient physical memory");
			return false;
		}

		pageTable=new TranslationEntry[numPages];

		for(int i=0;i<numPages;i++){
			int physicalP=UserKernel.allocPage();
			if(physicalP<0){
				Lib.debug(dbgProcess, "\tunable to allocate pages");
				for(int j=0;j<i;j++){
					if(pageTable[j].valid){
						UserKernel.freePage(pageTable[j].ppn);
						pageTable[j].valid=false;
					}
				}
				coff.close();
				return false;
			}
			pageTable[i]=new TranslationEntry(i,physicalP,true,false,false,false);
		}
		// load sections
		for (int s = 0; s < coff.getNumSections(); s++) {
			CoffSection section = coff.getSection(s);

			Lib.debug(dbgProcess, "\tinitializing " + section.getName()
					+ " section (" + section.getLength() + " pages)");

			for (int i = 0; i < section.getLength(); i++) {
				int vpn = section.getFirstVPN() + i;

				int ppn=pageTable[vpn].ppn;
				section.loadPage(i, ppn);
				if(section.isReadOnly())
					pageTable[vpn].readOnly=true;
			}
		}

		return true;
	}

	/**
	 * Release any resources allocated by <tt>loadSections()</tt>.
	 */
	protected void unloadSections() {

		for(int i=0;i<pageTable.length;i++){
			if(pageTable[i].valid){
				UserKernel.freePage(pageTable[i].ppn);
				pageTable[i].valid=false;
			}
		}
		coff.close();
	}

	/**
	 * Initialize the processor's registers in preparation for running the
	 * program loaded into this process. Set the PC register to point at the
	 * start function, set the stack pointer register to point at the top of the
	 * stack, set the A0 and A1 registers to argc and argv, respectively, and
	 * initialize all other registers to 0.
	 */
	public void initRegisters() {
		Processor processor = Machine.processor();

		// by default, everything's 0
		for (int i = 0; i < processor.numUserRegisters; i++)
			processor.writeRegister(i, 0);

		// initialize PC and SP according
		processor.writeRegister(Processor.regPC, initialPC);
		processor.writeRegister(Processor.regSP, initialSP);

		// initialize the first two argument registers to argc and argv
		processor.writeRegister(Processor.regA0, argc);
		processor.writeRegister(Processor.regA1, argv);
	}

	/**
	 *  help function for system calls
	 */

	private boolean checkVirtualAddress(int vAddress) {
		int locatedPage = Processor.pageFromAddress(vAddress);
		if (locatedPage >= 0 && locatedPage < numPages)
			return true;
		else
			return false;
	}

	/**
	 * chec if the file descriptor is 2-15 and it is pointed to a file
	 * @param fileDescriptor
	 * @return
	 */

	private boolean checkFileDescriptor(int fileDescriptor) {
		if (fileDescriptor >= openFileMap.length || fileDescriptor < 2)
			return false;
		if (openFileMap[fileDescriptor] == null)
			return false;
		return true;
	}

	/**
	 * Handle the halt() system call.
	 */
	private int handleHalt() {

		if (pid == ROOT)
			Machine.halt();
		else
			Lib.assertNotReached("Machine.halt() did not halt machine!");
		return 0;
	}

	private void handleExit(int status){
		Lib.debug(dbgProcess, "handleExit()");

		//close files
		for (int i=0; i!=16; ++i){
			if (openFileMap[i]!=null){
				handleClose(i);
			}
		}

		//change exitStatus
		exitStatus = status;

		//change all child processes' parent to ROOT
		if (children!=null && !children.isEmpty()){
			for (int cid: children.keySet()){
				children.get(cid).parentid = ROOT;
			}
		}
		children = null;

		//release memory
		unloadSections();

		//kill the current KThred, if it's root, terminate the Kernel
		if (pid==ROOT){
			Lib.debug(dbgProcess, "ROOT terminited!");
			Kernel.kernel.terminate();
		}
		else {
			KThread.currentThread().finish();
		}
	}

	private int handleExec(int file, int argc, int argv){
		if(argv<0 || argc<0 || file<0)
			return -1;
		String fName=readVirtualMemoryString(file,255);

		if(fName==null)
			return -1;
		String args[]=new String[argc];

		int arg;

		byte temp[]=new byte[4];

		for(int i=0;i<argc;i++){
			if(readVirtualMemory(argv+i*4,temp)!=4)
				return -1;
			arg=Lib.bytesToInt(temp,0);

			if((args[i]=readVirtualMemoryString(arg,255))==null)
				return -1;
		}

		UserProcess child=UserProcess.newUserProcess();
		child.parentid=this.pid;

		if(child.execute(fName,args)){
			children.put(child.pid,child);
			return child.pid;
		}

		return -1;
	}

	private int handleJoin(int pid, int status){
		if (status<0 || pid<0)
			return -1;

		UserProcess child;

		if(children.containsKey(pid))
			child=children.get(pid);
		else
			return -1;

		child.thread.join();
		children.remove(pid);

		if(child.exitStatus == 0){
			byte stats[]=new byte[4];
			stats=Lib.bytesFromInt(child.exitStatus);
			int byteTransf=writeVirtualMemory(status,stats);

			if(byteTransf==4)
				return 1;
			else
				return 0;
		}
		return 0;
	}

	/**
	 *
	 * @param vAddr in memory system, the address is use
	 * @return
	 */

	private int handleCreat(int vAddr) {
		String filename;
		OpenFile file;

		if (!checkVirtualAddress(vAddr))
			return -1;

		// in the requirement, the passed in vAddr from user program is defined up to 255
		if ( (filename = this.readVirtualMemoryString(vAddr, 255)) == null )
			return -1;

		// a process should maintain up to 16 open filed at same time,
		// 0 for stdin and 1 for stdout. Thus, we have 14 available descriptor for opening/creating file
		if (availableDescriptors.size() < 1) {
			return -1;
		}

		// get physical address for the desired file, "true" means "create"
		if ( (file = ThreadedKernel.fileSystem.open(filename, true)) == null )
			return -1;

		// get next available file descriptor
		Integer fileDescriptor = availableDescriptors.remove(0);
		openFileMap[fileDescriptor] = file;

		return fileDescriptor;
	}

	private int handleOpen(int vAddr) {
		String filename;
		OpenFile file;

		if (!checkVirtualAddress(vAddr))
			return -1;

		// in the requirement, the passed in vAddr from user program is defined up to 255
		if ( (filename = this.readVirtualMemoryString(vAddr, 255)) == null )
			return -1;

		// a process should maintain up to 16 open filed at same time,
		// 0 for stdin and 1 for stdout. Thus, we have 14 available descriptor for opening/creating file
		if (availableDescriptors.size() < 1) {
			return -1;
		}

		// get physical address for the desired file, "true" means "open"
		if ( (file = ThreadedKernel.fileSystem.open(filename, false)) == null )
			return -1;

		// get next available file descriptor
		Integer fileDescriptor = availableDescriptors.remove(0);
		openFileMap[fileDescriptor] = file;

		return fileDescriptor;
	}

	/**
	 * Read data by opened file descriptor
	 * @param fileDescriptor
	 * @param bufAddr address that we are going to stored read data
	 * @param count
	 * @return
	 */

	private int handleRead(int fileDescriptor, int bufAddr, int count) {
		int byteHaveRead;
		int byteHaveWritten;
		byte tempBuffer[] = new byte[count];

		if (!checkVirtualAddress(bufAddr))
			return -1;

		// the fileDescriptor should not be out of range (2-15)
		if (!checkFileDescriptor(fileDescriptor))
			return -1;

		// read the data to tempBuffer in kernel first before putting into physical memory
		if ( (byteHaveRead = openFileMap[fileDescriptor].read(tempBuffer, 0, count)) == -1)
			return -1;

		// put the read data that from file system, into physical memory where bufAddr represents
		if ( (byteHaveWritten = writeVirtualMemory(bufAddr, tempBuffer, 0, byteHaveRead)) != byteHaveWritten)
			return -1;

		return byteHaveWritten;
	}

	private int handleWrite(int fileDescriptor, int bufAddr, int count) {
		int byteHaveRead;
		int bytesHaveWritten;
		byte tempBuffer[] = new byte[count];
		if (!checkVirtualAddress(bufAddr))
			return -1;

		// the fileDescriptor should not be out of range (2-15)
		if (!checkFileDescriptor(fileDescriptor))
			return -1;

		;		// may be read 0 byte, but that's fine, it's is not an error,
		// Also, readVirtualMemory won't reutrn anything less than -1
		byteHaveRead = readVirtualMemory(bufAddr, tempBuffer);

		// if bytesHaveWritten is -1, then return -1, that is an error
		bytesHaveWritten = openFileMap[fileDescriptor].write(tempBuffer, 0, byteHaveRead);

		return bytesHaveWritten;
	}

	private int handleClose(int descriptor){
		OpenFile file = openFileMap[descriptor];
		if (file == null)
			return -1;

		file.close();
		openFileMap[descriptor] = null;
		availableDescriptors.add(descriptor);
		return 0;
	}

	private int handleUnlink(int vAddr){
		String filename = this.readVirtualMemoryString(vAddr,255);
		if (filename == null)
			return -1;
		if (ThreadedKernel.fileSystem.remove(filename))
			return 0;
		else
			return -1;
	}

	private static final int syscallHalt = 0, syscallExit = 1, syscallExec = 2,
			syscallJoin = 3, syscallCreate = 4, syscallOpen = 5,
			syscallRead = 6, syscallWrite = 7, syscallClose = 8,
			syscallUnlink = 9;

	/**
	 * Handle a syscall exception. Called by <tt>handleException()</tt>. The
	 * <i>syscall</i> argument identifies which syscall the user executed:
	 *
	 * <table>
	 * <tr>
	 * <td>syscall#</td>
	 * <td>syscall prototype</td>
	 * </tr>
	 * <tr>
	 * <td>0</td>
	 * <td><tt>void halt();</tt></td>
	 * </tr>
	 * <tr>
	 * <td>1</td>
	 * <td><tt>void exit(int status);</tt></td>
	 * </tr>
	 * <tr>
	 * <td>2</td>
	 * <td><tt>int  exec(char *name, int argc, char **argv);
	 * 								</tt></td>
	 * </tr>
	 * <tr>
	 * <td>3</td>
	 * <td><tt>int  join(int pid, int *status);</tt></td>
	 * </tr>
	 * <tr>
	 * <td>4</td>
	 * <td><tt>int  creat(char *name);</tt></td>
	 * </tr>
	 * <tr>
	 * <td>5</td>
	 * <td><tt>int  open(char *name);</tt></td>
	 * </tr>
	 * <tr>
	 * <td>6</td>
	 * <td><tt>int  read(int fd, char *buffer, int size);
	 * 								</tt></td>
	 * </tr>
	 * <tr>
	 * <td>7</td>
	 * <td><tt>int  write(int fd, char *buffer, int size);
	 * 								</tt></td>
	 * </tr>
	 * <tr>
	 * <td>8</td>
	 * <td><tt>int  close(int fd);</tt></td>
	 * </tr>
	 * <tr>
	 * <td>9</td>
	 * <td><tt>int  unlink(char *name);</tt></td>
	 * </tr>
	 * </table>
	 *
	 * @param syscall the syscall number.
	 * @param a0 the first syscall argument.
	 * @param a1 the second syscall argument.
	 * @param a2 the third syscall argument.
	 * @param a3 the fourth syscall argument.
	 * @return the value to be returned to the user.
	 */
	public int handleSyscall(int syscall, int a0, int a1, int a2, int a3) {
		switch (syscall) {
			case syscallHalt:
				return handleHalt();
			case syscallExit:
				handleExit(a0);
				return 0;
			case syscallExec:
				return handleExec(a0,a1,a2);
			case syscallJoin:
				return handleJoin(a0,a1);
			case syscallCreate:
				return handleCreat(a0);
			case syscallOpen:
				return handleOpen(a0);
			case syscallRead:
				return handleRead(a0, a1, a2);
			case syscallWrite:
				return handleWrite(a0, a1, a2);
			case syscallClose:
				return handleClose(a0);
			case syscallUnlink:
				return handleUnlink(a0);
			default:
				Lib.debug(dbgProcess, "Unknown syscall " + syscall);
				Lib.assertNotReached("Unknown system call!");
		}
		return 0;
	}

	/**
	 * Handle a user exception. Called by <tt>UserKernel.exceptionHandler()</tt>
	 * . The <i>cause</i> argument identifies which exception occurred; see the
	 * <tt>Processor.exceptionZZZ</tt> constants.
	 *
	 * @param cause the user exception that occurred.
	 */
	public void handleException(int cause) {
		Processor processor = Machine.processor();

		switch (cause) {
			case Processor.exceptionSyscall:
				int result = handleSyscall(processor.readRegister(Processor.regV0),
						processor.readRegister(Processor.regA0),
						processor.readRegister(Processor.regA1),
						processor.readRegister(Processor.regA2),
						processor.readRegister(Processor.regA3));
				processor.writeRegister(Processor.regV0, result);
				processor.advancePC();
				break;

			default:
				Lib.debug(dbgProcess, "Unexpected exception: "
						+ Processor.exceptionNames[cause]);
				Lib.assertNotReached("Unexpected exception");
		}
	}

	/** The program being run by this process. */
	protected Coff coff;

	/** This process's page table. */
	protected TranslationEntry[] pageTable;

	/** The number of contiguous pages occupied by the program. */
	protected int numPages;

	/** The number of pages in the program's stack. */
	protected final int stackPages = 8;

	private int initialPC, initialSP;

	private int argc, argv;

	private static final int pageSize = Processor.pageSize;

	private static final char dbgProcess = 'a';

	/** The list to track available descriptors. */
	ArrayList<Integer> availableDescriptors;

	/** Map file descriptor to open file. */
	OpenFile openFileMap[] = new OpenFile[16];

	private static final int ROOT = 0;

	private static int processCount=ROOT;
	//this process's id
	private int pid;
	//parent process's id
	private int parentid = ROOT;
	//childProcess
	private HashMap<Integer,UserProcess> children = null;
	/*
	exitStatus:
	0 exit normally:
	-1 exit with Exception
	 */
	private int exitStatus;

	private UThread thread;
}